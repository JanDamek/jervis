package com.jervis.qualifier

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.dto.filtering.FilterAction
import com.jervis.dto.filtering.FilterSourceType
import com.jervis.dto.pipeline.ActionType
import com.jervis.entity.TaskDocument
import com.jervis.knowledgebase.model.FullIngestResult
import com.jervis.service.background.AutoTaskCreationService
import com.jervis.service.background.TaskService
import com.jervis.service.filtering.FilteringRulesService
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * Routes a task based on KB ingest result.
 *
 * Extracted from SimpleQualifierAgent so it can be called both:
 * - Synchronously from qualification (legacy)
 * - From the /internal/kb-done callback (async fire-and-forget flow)
 *
 * EPIC 2: Enhanced with ActionTypeInferrer for structured routing
 * and AutoTaskCreationService for automatic task creation from findings.
 */
@Service
class KbResultRouter(
    private val taskService: TaskService,
    private val actionTypeInferrer: ActionTypeInferrer,
    private val autoTaskCreationService: AutoTaskCreationService,
    private val filteringRulesService: FilteringRulesService,
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val SCHEDULE_LEAD_DAYS = 2L

        val COMPLEX_ACTIONS = setOf(
            "decompose_issue", "analyze_code", "create_application",
            "review_code", "design_architecture",
        )
    }

    data class RoutingDecision(
        val state: TaskStateEnum,
        val reason: String,
        val scheduledCopyCreated: Boolean = false,
    )

    /**
     * Route task based on KB analysis result.
     *
     * EPIC 2: Enhanced routing with structured ActionType/Complexity inference
     * and automatic task creation for actionable findings.
     *
     * @param onProgress optional callback for emitting progress steps (UI updates)
     */
    suspend fun routeTask(
        task: TaskDocument,
        result: FullIngestResult,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit = { _, _ -> },
    ): RoutingDecision {
        // EPIC 2: Infer structured fields from KB result
        val inferred = actionTypeInferrer.infer(result)

        // Step 1: Not actionable → indexed only
        if (!result.hasActionableContent) {
            logger.info {
                "KB_ROUTE: taskId=${task.id} reason=noActionRequired summary=${result.summary.take(100)}"
            }
            onProgress(
                "Obsah informační, nevyžaduje akci → zaindexováno",
                mapOf("step" to "routing", "agent" to "simple_qualifier", "route" to "Informační obsah", "result" to "Zaindexováno"),
            )
            onProgress("Hotovo", mapOf("step" to "done", "agent" to "simple_qualifier"))
            return RoutingDecision(TaskStateEnum.DONE, "info_only")
        }

        // EPIC 10-S3: Evaluate filtering rules before routing
        val filterAction = evaluateFilters(task, result)
        if (filterAction == FilterAction.IGNORE) {
            logger.info {
                "KB_ROUTE: taskId=${task.id} reason=filtered action=IGNORE"
            }
            onProgress(
                "Filtrováno → ignorováno",
                mapOf("step" to "routing", "agent" to "simple_qualifier", "route" to "Filtrováno", "result" to "Ignorováno"),
            )
            onProgress("Hotovo", mapOf("step" to "done", "agent" to "simple_qualifier"))
            return RoutingDecision(TaskStateEnum.DONE, "filtered_ignore")
        }
        if (filterAction != null && filterAction != FilterAction.NORMAL) {
            logger.info {
                "KB_ROUTE: taskId=${task.id} filterAction=$filterAction"
            }
        }

        // Step 2: Simple actions → handle locally without orchestrator
        // EPIC 10-S3: URGENT/HIGH_PRIORITY filter overrides → treat as complex
        val hasComplex = filterAction in listOf(FilterAction.URGENT, FilterAction.HIGH_PRIORITY) ||
            result.suggestedActions.any { it in COMPLEX_ACTIONS }
        if (!hasComplex) {
            handleSimpleAction(task, result, onProgress)
            logger.info {
                "KB_ROUTE: taskId=${task.id} reason=simpleAction actions=${result.suggestedActions}"
            }

            // EPIC 2: Also create structured auto-task for tracked pipeline processing
            try {
                autoTaskCreationService.createFromQualifierFinding(task, result, inferred)
            } catch (e: Exception) {
                logger.warn(e) { "AUTO_TASK_CREATION_FAILED: taskId=${task.id} (non-fatal)" }
            }

            return RoutingDecision(TaskStateEnum.DONE, "simple_action_handled")
        }

        // EPIC 2: For complex actionable content, create auto-task and let it handle routing
        try {
            val autoTask = autoTaskCreationService.createFromQualifierFinding(task, result, inferred)
            if (autoTask != null) {
                logger.info {
                    "KB_ROUTE: taskId=${task.id} autoTaskId=${autoTask.id} " +
                        "actionType=${inferred.actionType} complexity=${inferred.estimatedComplexity}"
                }
                onProgress(
                    "Vytvořen automatický úkol: ${inferred.actionType.name} (${inferred.estimatedComplexity.name})",
                    mapOf(
                        "step" to "auto_task",
                        "agent" to "simple_qualifier",
                        "actionType" to inferred.actionType.name,
                        "complexity" to inferred.estimatedComplexity.name,
                    ),
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "AUTO_TASK_CREATION_FAILED: taskId=${task.id} (non-fatal, continuing with legacy routing)" }
        }

        // Step 3: Complex + assigned to me → immediate
        if (result.isAssignedToMe) {
            logger.info {
                "KB_ROUTE: taskId=${task.id} reason=assignedToMe urgency=${result.urgency}"
            }
            onProgress(
                "Přiřazený úkol → do fronty pro MOZEK",
                mapOf("step" to "routing", "agent" to "simple_qualifier", "route" to "Přiřazeno mně", "result" to "Čeká na MOZEK"),
            )
            return RoutingDecision(TaskStateEnum.READY_FOR_GPU, "assigned_to_me")
        }

        // Step 4: Complex + future deadline → schedule or immediate
        val suggestedDeadline = result.suggestedDeadline
        if (result.hasFutureDeadline && suggestedDeadline != null) {
            val deadline = parseDeadline(suggestedDeadline)
            if (deadline != null) {
                val now = Instant.now()

                // Deadline already in the past → ignore scheduling (old email / stale content)
                if (deadline.isBefore(now)) {
                    logger.info {
                        "KB_ROUTE: taskId=${task.id} reason=deadlineAlreadyPassed deadline=$suggestedDeadline"
                    }
                    onProgress(
                        "Prošlý termín ($suggestedDeadline) → zpracovat bez plánování",
                        mapOf("step" to "routing", "agent" to "simple_qualifier", "route" to "Prošlý termín", "result" to "Čeká na MOZEK"),
                    )
                    return RoutingDecision(TaskStateEnum.READY_FOR_GPU, "deadline_already_passed")
                }

                val leadTime = Duration.ofDays(SCHEDULE_LEAD_DAYS)
                val scheduledAt = deadline.minus(leadTime)

                if (scheduledAt.isBefore(now)) {
                    logger.info {
                        "KB_ROUTE: taskId=${task.id} reason=deadlineTooClose deadline=$suggestedDeadline"
                    }
                    onProgress(
                        "Blízký termín → do fronty pro MOZEK",
                        mapOf("step" to "routing", "agent" to "simple_qualifier", "route" to "Blízký termín", "result" to "Čeká na MOZEK"),
                    )
                    return RoutingDecision(TaskStateEnum.READY_FOR_GPU, "deadline_too_close")
                }

                createScheduledCopy(task, result, scheduledAt)
                logger.info {
                    "KB_ROUTE: taskId=${task.id} reason=scheduled scheduledAt=$scheduledAt deadline=$suggestedDeadline"
                }
                onProgress(
                    "Naplánováno na $scheduledAt (termín: $suggestedDeadline)",
                    mapOf("step" to "routing", "agent" to "simple_qualifier", "route" to "Naplánováno", "result" to "Zaindexováno + naplánováno"),
                )
                return RoutingDecision(TaskStateEnum.DONE, "scheduled", scheduledCopyCreated = true)
            }
        }

        // Step 5: Complex actionable → execute when available
        logger.info {
            "KB_ROUTE: taskId=${task.id} reason=complexActionable urgency=${result.urgency} actions=${result.suggestedActions} " +
                "actionType=${inferred.actionType} complexity=${inferred.estimatedComplexity}"
        }
        onProgress(
            "Akční obsah → do fronty pro MOZEK",
            mapOf("step" to "routing", "agent" to "simple_qualifier", "route" to "Vyžaduje akci", "result" to "Čeká na MOZEK"),
        )
        return RoutingDecision(TaskStateEnum.READY_FOR_GPU, "complex_actionable")
    }

    private suspend fun handleSimpleAction(
        task: TaskDocument,
        result: FullIngestResult,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit,
    ) {
        when {
            result.suggestedActions.any { it in listOf("reply_email", "answer_question") } -> {
                taskService.createTask(
                    taskType = TaskTypeEnum.USER_TASK,
                    content = "Odpovědět na: ${result.summary}",
                    clientId = ClientId(task.clientId.value),
                    projectId = task.projectId?.let { ProjectId(it.value) },
                    correlationId = "action:${task.correlationId}",
                    sourceUrn = task.sourceUrn,
                    taskName = "Odpovědět: ${task.content.lineSequence().firstOrNull()?.take(80) ?: "email"}",
                    state = TaskStateEnum.NEW,
                )
                onProgress("Vytvořen úkol pro odpověď", mapOf("step" to "simple_action", "agent" to "simple_qualifier", "actionType" to "Odpověď na email"))
                onProgress("Hotovo", mapOf("step" to "done", "agent" to "simple_qualifier"))
            }
            "schedule_meeting" in result.suggestedActions -> {
                val deadline = result.suggestedDeadline?.let { parseDeadline(it) }
                if (deadline != null) {
                    createScheduledCopy(task, result, deadline.minus(Duration.ofDays(1)))
                    onProgress("Vytvořen reminder pro schůzku", mapOf("step" to "simple_action", "agent" to "simple_qualifier", "actionType" to "Naplánovat schůzku"))
                }
                onProgress("Hotovo", mapOf("step" to "done", "agent" to "simple_qualifier"))
            }
            else -> {
                onProgress("Zpracováno — jednoduchá akce", mapOf("step" to "simple_action", "agent" to "simple_qualifier", "actionType" to "Potvrzení"))
                onProgress("Hotovo", mapOf("step" to "done", "agent" to "simple_qualifier"))
            }
        }
    }

    private suspend fun createScheduledCopy(
        originalTask: TaskDocument,
        result: FullIngestResult,
        scheduledAt: Instant,
    ): TaskDocument {
        return taskService.createTask(
            taskType = TaskTypeEnum.SCHEDULED_TASK,
            content = originalTask.content,
            clientId = ClientId(originalTask.clientId.value),
            projectId = originalTask.projectId?.let { ProjectId(it.value) },
            correlationId = "scheduled:${originalTask.correlationId}",
            sourceUrn = originalTask.sourceUrn,
            attachments = originalTask.attachments,
            taskName = "Naplánováno: ${result.summary.take(100)}",
        ).let { created ->
            val withSchedule = created.copy(scheduledAt = scheduledAt)
            taskService.updateState(withSchedule, TaskStateEnum.NEW)
        }
    }

    /**
     * EPIC 10-S3: Evaluate filtering rules for a task.
     * Maps SourceUrn to FilterSourceType and calls FilteringRulesService.
     */
    private suspend fun evaluateFilters(task: TaskDocument, result: FullIngestResult): FilterAction? {
        return try {
            val sourceType = extractSourceType(task.sourceUrn)
            filteringRulesService.evaluate(
                sourceType = sourceType,
                subject = result.summary.take(200),
                body = task.content.take(2000),
                labels = result.entities,
            )
        } catch (e: Exception) {
            logger.warn(e) { "FILTER_EVAL_FAILED: taskId=${task.id} (non-fatal, continuing)" }
            null
        }
    }

    private fun extractSourceType(sourceUrn: SourceUrn): FilterSourceType {
        val urn = sourceUrn.value
        return when {
            urn.startsWith("email::") -> FilterSourceType.EMAIL
            urn.startsWith("jira::") -> FilterSourceType.JIRA
            urn.startsWith("github-issue::") || urn.startsWith("gitlab-issue::") || urn.startsWith("git::") -> FilterSourceType.GIT
            urn.startsWith("confluence::") -> FilterSourceType.WIKI
            urn.startsWith("chat::") -> FilterSourceType.CHAT
            else -> FilterSourceType.ALL
        }
    }

    private fun parseDeadline(isoString: String): Instant? {
        return try {
            Instant.parse(isoString)
        } catch (_: Exception) {
            try {
                java.time.OffsetDateTime.parse(isoString).toInstant()
            } catch (_: Exception) {
                try {
                    java.time.LocalDateTime.parse(isoString).toInstant(java.time.ZoneOffset.UTC)
                } catch (_: Exception) {
                    logger.debug { "Could not parse deadline: $isoString" }
                    null
                }
            }
        }
    }
}
