package com.jervis.qualifier

import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.TaskDocument
import com.jervis.knowledgebase.model.Attachment
import com.jervis.knowledgebase.model.FullIngestRequest
import com.jervis.knowledgebase.model.FullIngestResult
import com.jervis.service.background.TaskService
import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.service.storage.DirectoryStructureService
import com.jervis.service.text.TikaTextExtractionService
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Simplified qualification agent that delegates all heavy lifting to KB microservice.
 *
 * Flow:
 * 1. Extract text from task content (Tika)
 * 2. Read attachment files from storage
 * 3. Call KB's ingestFull endpoint (handles vision/OCR, RAG, graph, summary generation)
 * 4. Three-way routing based on KB analysis:
 *
 *    A) isAssignedToMe=true AND hasActionableContent=true
 *       → READY_FOR_GPU (immediate, high priority)
 *
 *    B) hasFutureDeadline=true AND hasActionableContent=true
 *       ├─ deadline < scheduleLeadDays away → READY_FOR_GPU (too close, do now)
 *       └─ deadline >= scheduleLeadDays away → create SCHEDULED_TASK copy
 *            scheduledAt = deadline - scheduleLeadDays
 *            original task → DISPATCHED_GPU (indexed, done)
 *
 *    C) hasActionableContent=true (no assignment, no deadline)
 *       → READY_FOR_GPU (execute when available)
 *
 *    D) hasActionableContent=false
 *       → DISPATCHED_GPU (indexed only, no action needed)
 */
@Service
class SimpleQualifierAgent(
    private val knowledgeClient: com.jervis.configuration.KnowledgeServiceRestClient,
    private val taskService: TaskService,
    private val tikaTextExtractionService: TikaTextExtractionService,
    private val directoryStructureService: DirectoryStructureService,
    private val brainWriteService: com.jervis.service.brain.BrainWriteService,
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        /** Default schedule lead time in days. Tasks with deadlines further than
         *  this many days in the future will be scheduled, not executed immediately.
         *  TODO: Make configurable per client (e.g., ClientDocument.scheduleLeadDays) */
        const val SCHEDULE_LEAD_DAYS = 2L

        /** Actions that require full orchestrator (30B model, complex reasoning). */
        val COMPLEX_ACTIONS = setOf(
            "decompose_issue", "analyze_code", "create_application",
            "review_code", "design_architecture",
        )
    }

    suspend fun run(
        task: TaskDocument,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit = { _, _ -> },
    ): String {
        logger.info { "SIMPLE_QUALIFIER_START | taskId=${task.id} | type=${task.type} | correlationId=${task.correlationId}" }

        try {
            onProgress("Zahajuji kvalifikaci...", mapOf("step" to "agent_start", "agent" to "simple_qualifier"))

            // 1. Extract clean text from content
            val cleanedContent = tikaTextExtractionService.extractPlainText(
                content = task.content,
                fileName = "task-${task.correlationId}.txt",
            )
            onProgress("Text extrahován (${cleanedContent.length} znaků)", mapOf("step" to "text_extracted", "agent" to "simple_qualifier", "chars" to cleanedContent.length.toString()))

            // 2. Load attachments from storage
            val attachments = loadAttachments(task)
            logger.info { "SIMPLE_QUALIFIER_ATTACHMENTS | taskId=${task.id} | count=${attachments.size}" }
            if (attachments.isNotEmpty()) {
                onProgress("Načteno ${attachments.size} příloh", mapOf("step" to "attachments_loaded", "agent" to "simple_qualifier", "count" to attachments.size.toString()))
            }

            // 3. Build request for KB
            onProgress("Odesílám do KB služby...", mapOf("step" to "kb_call_start", "agent" to "simple_qualifier"))
            val request = FullIngestRequest(
                clientId = task.clientId,
                projectId = task.projectId,
                sourceUrn = task.correlationId,
                sourceType = mapTaskTypeToSourceType(task.type),
                subject = extractSubject(task),
                content = cleanedContent,
                metadata = buildMetadata(task),
                observedAt = Instant.now(),
                attachments = attachments,
            )

            // 4. Call KB's ingestFull endpoint with streaming progress
            // taskId + clientId enable push-based progress (Python → POST /internal/kb-progress → WebSocket)
            val result: FullIngestResult = knowledgeClient.ingestFullWithProgress(
                request,
                taskId = task.id?.toString() ?: "",
                clientId = task.clientId.toString(),
            ) { message, step, metadata ->
                onProgress(message, metadata + ("step" to step) + ("agent" to "simple_qualifier"))
            }

            logger.info {
                "SIMPLE_QUALIFIER_KB_RESULT | taskId=${task.id} | success=${result.success} | " +
                    "chunks=${result.chunksCount} | hasActionable=${result.hasActionableContent} | " +
                    "suggestedActions=${result.suggestedActions}"
            }

            if (!result.success) {
                val errorMsg = result.error ?: "KB ingest failed"
                logger.error { "SIMPLE_QUALIFIER_KB_FAILED | taskId=${task.id} | error=$errorMsg" }

                // Throw for connection errors so TaskQualificationService can handle retry logic
                if (isConnectionError(errorMsg)) {
                    throw RuntimeException("KB connection error: $errorMsg")
                }

                taskService.markAsError(task, errorMsg)
                return "FAILED: $errorMsg"
            }

            // 5. Cross-project aggregation: write actionable findings to brain Jira
            if (result.hasActionableContent) {
                writeToBrain(task, result)
            }

            // 6. Three-way routing based on KB analysis
            val routingDecision = routeTask(task, result, onProgress)

            taskService.updateState(task, routingDecision.state)

            logger.info {
                "SIMPLE_QUALIFIER_COMPLETE | taskId=${task.id} | " +
                    "finalState=${routingDecision.state} | route=${routingDecision.reason} | " +
                    "scheduledCopy=${routingDecision.scheduledCopyCreated} | " +
                    "summary=${result.summary.take(100)}"
            }

            return result.summary
        } catch (e: Exception) {
            logger.error(e) { "SIMPLE_QUALIFIER_ERROR | taskId=${task.id} | error=${e.message}" }
            throw e
        }
    }

    private data class RoutingDecision(
        val state: TaskStateEnum,
        val reason: String,
        val scheduledCopyCreated: Boolean = false,
    )

    /**
     * Task routing based on KB ingest analysis.
     *
     * Decision tree:
     * 1. Not actionable → indexed only (DISPATCHED_GPU)
     * 2. Actionable + only simple actions → handle locally (DISPATCHED_GPU)
     * 3. Actionable + complex + assigned to me → immediate (READY_FOR_GPU)
     * 4. Actionable + complex + future deadline → schedule or immediate
     * 5. Actionable + complex → immediate (READY_FOR_GPU)
     *
     * Note: No age-based filter — LLM (_generate_summary) decides actionability
     * even for old content (forgotten tasks, open issues, etc.)
     */
    private suspend fun routeTask(
        task: TaskDocument,
        result: FullIngestResult,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit,
    ): RoutingDecision {
        // Step 1: Not actionable → indexed only
        if (!result.hasActionableContent) {
            logger.info {
                "SIMPLE_QUALIFIER_ROUTE_DISPATCHED | taskId=${task.id} | " +
                    "reason=noActionRequired | summary=${result.summary.take(100)}"
            }
            onProgress(
                "Obsah informační, nevyžaduje akci → zaindexováno",
                mapOf("step" to "routing", "agent" to "simple_qualifier", "route" to "Informační obsah", "result" to "Zaindexováno"),
            )
            onProgress("Hotovo", mapOf("step" to "done", "agent" to "simple_qualifier"))
            return RoutingDecision(TaskStateEnum.DISPATCHED_GPU, "info_only")
        }

        // Step 2: Simple actions → handle locally without orchestrator
        val hasComplex = result.suggestedActions.any { it in COMPLEX_ACTIONS }
        if (!hasComplex) {
            handleSimpleAction(task, result, onProgress)
            logger.info {
                "SIMPLE_QUALIFIER_ROUTE_SIMPLE | taskId=${task.id} | " +
                    "actions=${result.suggestedActions} | summary=${result.summary.take(100)}"
            }
            return RoutingDecision(TaskStateEnum.DISPATCHED_GPU, "simple_action_handled")
        }

        // Step 3: Complex + assigned to me → immediate
        if (result.isAssignedToMe) {
            logger.info {
                "SIMPLE_QUALIFIER_ROUTE_IMMEDIATE | taskId=${task.id} | " +
                    "reason=assignedToMe | urgency=${result.urgency} | actions=${result.suggestedActions}"
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
                val leadTime = Duration.ofDays(SCHEDULE_LEAD_DAYS)
                val scheduledAt = deadline.minus(leadTime)

                // If deadline is too close, execute immediately
                if (scheduledAt.isBefore(now)) {
                    logger.info {
                        "SIMPLE_QUALIFIER_ROUTE_IMMEDIATE | taskId=${task.id} | " +
                            "reason=deadlineTooClose | deadline=${result.suggestedDeadline} | " +
                            "urgency=${result.urgency}"
                    }
                    onProgress(
                        "Blízký termín → do fronty pro MOZEK",
                        mapOf("step" to "routing", "agent" to "simple_qualifier", "route" to "Blízký termín", "result" to "Čeká na MOZEK"),
                    )
                    return RoutingDecision(TaskStateEnum.READY_FOR_GPU, "deadline_too_close")
                }

                // Deadline is far enough → create scheduled copy
                val scheduledCopy = createScheduledCopy(task, result, scheduledAt)
                logger.info {
                    "SIMPLE_QUALIFIER_ROUTE_SCHEDULED | taskId=${task.id} | " +
                        "scheduledTaskId=${scheduledCopy.id} | scheduledAt=$scheduledAt | " +
                        "deadline=${result.suggestedDeadline}"
                }
                onProgress(
                    "Naplánováno na ${scheduledAt} (termín: ${result.suggestedDeadline})",
                    mapOf("step" to "routing", "agent" to "simple_qualifier", "route" to "Naplánováno", "result" to "Zaindexováno + naplánováno"),
                )
                // Original task is dispatched (indexed), scheduled copy will fire later
                return RoutingDecision(TaskStateEnum.DISPATCHED_GPU, "scheduled", scheduledCopyCreated = true)
            }
        }

        // Step 5: Complex actionable → execute when available
        logger.info {
            "SIMPLE_QUALIFIER_ROUTE_GPU | taskId=${task.id} | " +
                "reason=complexActionableContent | urgency=${result.urgency} | " +
                "actions=${result.suggestedActions} | summary=${result.summary.take(100)}"
        }
        onProgress(
            "Akční obsah → do fronty pro MOZEK",
            mapOf("step" to "routing", "agent" to "simple_qualifier", "route" to "Vyžaduje akci", "result" to "Čeká na MOZEK"),
        )
        return RoutingDecision(TaskStateEnum.READY_FOR_GPU, "complex_actionable")
    }

    /**
     * Handle simple actions locally without involving the orchestrator (30B model).
     * Creates USER_TASKs, reminders, or just marks as done.
     */
    private suspend fun handleSimpleAction(
        task: TaskDocument,
        result: FullIngestResult,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit,
    ) {
        when {
            // Reply/answer needed → create USER_TASK for user to decide
            result.suggestedActions.any { it in listOf("reply_email", "answer_question") } -> {
                taskService.createTask(
                    taskType = TaskTypeEnum.USER_TASK,
                    content = "Odpovědět na: ${result.summary}",
                    clientId = ClientId(task.clientId.value),
                    projectId = task.projectId?.let { ProjectId(it.value) },
                    correlationId = "action:${task.correlationId}",
                    sourceUrn = task.sourceUrn,
                    taskName = "Odpovědět: ${extractSubject(task)?.take(80) ?: "email"}",
                    state = TaskStateEnum.NEW,
                )
                onProgress("Vytvořen úkol pro odpověď", mapOf("step" to "simple_action", "agent" to "simple_qualifier", "actionType" to "Odpověď na email"))
                onProgress("Hotovo", mapOf("step" to "done", "agent" to "simple_qualifier"))
            }
            // Meeting scheduling → create reminder if deadline available
            "schedule_meeting" in result.suggestedActions -> {
                val deadline = result.suggestedDeadline?.let { parseDeadline(it) }
                if (deadline != null) {
                    createScheduledCopy(task, result, deadline.minus(Duration.ofDays(1)))
                    onProgress("Vytvořen reminder pro schůzku", mapOf("step" to "simple_action", "agent" to "simple_qualifier", "actionType" to "Naplánovat schůzku"))
                }
                onProgress("Hotovo", mapOf("step" to "done", "agent" to "simple_qualifier"))
            }
            // Acknowledge, forward_info, etc. → done, just indexed
            else -> {
                onProgress("Zpracováno — jednoduchá akce", mapOf("step" to "simple_action", "agent" to "simple_qualifier", "actionType" to "Potvrzení"))
                onProgress("Hotovo", mapOf("step" to "done", "agent" to "simple_qualifier"))
            }
        }
    }

    /**
     * Create a scheduled copy of the task for future execution.
     *
     * The original task is marked as DISPATCHED_GPU (indexed only).
     * The copy is created as a new SCHEDULED_TASK with scheduledAt set,
     * which the BackgroundEngine scheduler loop will pick up automatically.
     */
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
            // Set scheduledAt on the created task
            val withSchedule = created.copy(scheduledAt = scheduledAt)
            taskService.updateState(withSchedule, TaskStateEnum.NEW)
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

    private fun loadAttachments(task: TaskDocument): List<Attachment> {
        if (task.attachments.isEmpty()) {
            return emptyList()
        }

        return task.attachments.mapNotNull { attachmentMeta: AttachmentMetadata ->
            try {
                val file = File(attachmentMeta.storagePath)
                if (file.exists() && file.isFile) {
                    Attachment(
                        filename = attachmentMeta.filename,
                        contentType = attachmentMeta.mimeType,
                        data = file.readBytes(),
                    )
                } else {
                    logger.warn { "Attachment file not found: ${attachmentMeta.storagePath}" }
                    null
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load attachment: ${attachmentMeta.storagePath}" }
                null
            }
        }
    }

    private fun mapTaskTypeToSourceType(taskType: TaskTypeEnum): String {
        return when (taskType) {
            TaskTypeEnum.EMAIL_PROCESSING -> "email"
            TaskTypeEnum.WIKI_PROCESSING -> "confluence"
            TaskTypeEnum.BUGTRACKER_PROCESSING -> "jira"
            TaskTypeEnum.GIT_PROCESSING -> "git"
            TaskTypeEnum.USER_INPUT_PROCESSING -> "chat"
            TaskTypeEnum.USER_TASK -> "user_task"
            TaskTypeEnum.SCHEDULED_TASK -> "scheduled"
            TaskTypeEnum.LINK_PROCESSING -> "link"
            TaskTypeEnum.MEETING_PROCESSING -> "meeting"
            TaskTypeEnum.IDLE_REVIEW -> "idle_review"
        }
    }

    private fun extractSubject(task: TaskDocument): String? {
        return task.content.lineSequence().firstOrNull()?.take(200)
    }

    private fun isConnectionError(message: String): Boolean =
        message.contains("connection", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true) ||
            message.contains("socket", ignoreCase = true) ||
            message.contains("network", ignoreCase = true) ||
            message.contains("prematurely closed", ignoreCase = true)

    /**
     * Write actionable finding to brain Jira for cross-project aggregation.
     * Non-critical: failure does not block qualification.
     *
     * Deduplicates by correlationId label to prevent duplicate issues.
     */
    private suspend fun writeToBrain(task: TaskDocument, result: FullIngestResult) {
        try {
            if (!brainWriteService.isConfigured()) return

            val summary = "${mapTaskTypeToSourceType(task.type).uppercase()}: ${result.summary.take(150)}"
            val description = buildString {
                appendLine("**Source:** ${task.type.name}")
                appendLine("**Correlation ID:** ${task.correlationId}")
                task.projectId?.let { appendLine("**Project ID:** $it") }
                appendLine("**Urgency:** ${result.urgency}")
                if (result.suggestedActions.isNotEmpty()) {
                    appendLine("**Suggested actions:** ${result.suggestedActions}")
                }
                appendLine()
                appendLine(result.summary)
            }

            val labels = listOf("auto-ingest", mapTaskTypeToSourceType(task.type))

            // Deduplication: check if issue with this correlationId already exists
            val existing = try {
                brainWriteService.searchIssues(
                    jql = "labels = \"corr:${task.correlationId}\"",
                    maxResults = 1,
                )
            } catch (_: Exception) {
                emptyList()
            }

            if (existing.isNotEmpty()) {
                logger.debug { "BRAIN_WRITE_SKIP | taskId=${task.id} | correlationId=${task.correlationId} | already exists" }
                return
            }

            brainWriteService.createIssue(
                summary = summary,
                description = description,
                labels = labels + "corr:${task.correlationId}",
            )

            logger.info { "BRAIN_WRITE_OK | taskId=${task.id} | correlationId=${task.correlationId}" }
        } catch (e: Exception) {
            // Non-critical: log and continue
            logger.warn(e) { "BRAIN_WRITE_FAILED | taskId=${task.id} | error=${e.message}" }
        }
    }

    private fun buildMetadata(task: TaskDocument): Map<String, String> {
        return buildMap {
            put("taskId", task.id?.toString() ?: "unknown")
            put("taskType", task.type.name)
            put("correlationId", task.correlationId)
            task.projectId?.let { put("projectId", it.toString()) }
        }
    }
}
