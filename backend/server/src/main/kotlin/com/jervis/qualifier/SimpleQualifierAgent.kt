package com.jervis.qualifier

import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.TaskDocument
import com.jervis.knowledgebase.KnowledgeService
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
    private val knowledgeService: KnowledgeService,
    private val taskService: TaskService,
    private val tikaTextExtractionService: TikaTextExtractionService,
    private val directoryStructureService: DirectoryStructureService,
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        /** Default schedule lead time in days. Tasks with deadlines further than
         *  this many days in the future will be scheduled, not executed immediately.
         *  TODO: Make configurable per client (e.g., ClientDocument.scheduleLeadDays) */
        const val SCHEDULE_LEAD_DAYS = 2L
    }

    suspend fun run(
        task: TaskDocument,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit = { _, _ -> },
    ): String {
        logger.info { "SIMPLE_QUALIFIER_START | taskId=${task.id} | type=${task.type} | correlationId=${task.correlationId}" }

        onProgress("Zpracovávám obsah...", mapOf("step" to "start", "agent" to "simple_qualifier"))

        try {
            // 1. Extract clean text from content
            val cleanedContent = tikaTextExtractionService.extractPlainText(
                content = task.content,
                fileName = "task-${task.correlationId}.txt",
            )

            // 2. Load attachments from storage
            val attachments = loadAttachments(task)
            logger.info { "SIMPLE_QUALIFIER_ATTACHMENTS | taskId=${task.id} | count=${attachments.size}" }

            // 3. Build request for KB
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

            onProgress("Indexuji do knowledge base...", mapOf("step" to "ingest", "agent" to "simple_qualifier"))

            // 4. Call KB's ingestFull endpoint
            val result: FullIngestResult = knowledgeService.ingestFull(request)

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

            // 5. Three-way routing based on KB analysis
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
     * Three-way task routing based on KB ingest analysis.
     *
     * Decision tree:
     * 1. Assigned to me + actionable → immediate (READY_FOR_GPU)
     * 2. Future deadline + actionable → schedule or immediate (depends on lead time)
     * 3. Actionable (no assignment/deadline) → immediate (READY_FOR_GPU)
     * 4. Not actionable → indexed only (DISPATCHED_GPU)
     */
    private suspend fun routeTask(
        task: TaskDocument,
        result: FullIngestResult,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit,
    ): RoutingDecision {
        // Case D: Not actionable → indexed only
        if (!result.hasActionableContent) {
            logger.info {
                "SIMPLE_QUALIFIER_ROUTE_DISPATCHED | taskId=${task.id} | " +
                    "reason=noActionRequired | summary=${result.summary.take(100)}"
            }
            onProgress("Hotovo - obsah zaindexován", mapOf("step" to "done", "agent" to "simple_qualifier"))
            return RoutingDecision(TaskStateEnum.DISPATCHED_GPU, "info_only")
        }

        // Case A: Assigned to me → immediate
        if (result.isAssignedToMe) {
            logger.info {
                "SIMPLE_QUALIFIER_ROUTE_IMMEDIATE | taskId=${task.id} | " +
                    "reason=assignedToMe | urgency=${result.urgency} | actions=${result.suggestedActions}"
            }
            onProgress(
                "Úkol je přiřazen - přesouvám do fronty pro okamžité zpracování...",
                mapOf("step" to "route_gpu_assigned", "agent" to "simple_qualifier"),
            )
            return RoutingDecision(TaskStateEnum.READY_FOR_GPU, "assigned_to_me")
        }

        // Case B: Future deadline → schedule or immediate
        if (result.hasFutureDeadline && result.suggestedDeadline != null) {
            val deadline = parseDeadline(result.suggestedDeadline)
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
                        "Blízký termín - přesouvám do fronty pro okamžité zpracování...",
                        mapOf("step" to "route_gpu_deadline", "agent" to "simple_qualifier"),
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
                    mapOf("step" to "route_scheduled", "agent" to "simple_qualifier"),
                )
                // Original task is dispatched (indexed), scheduled copy will fire later
                return RoutingDecision(TaskStateEnum.DISPATCHED_GPU, "scheduled", scheduledCopyCreated = true)
            }
        }

        // Case C: Actionable but no assignment or deadline → execute when available
        logger.info {
            "SIMPLE_QUALIFIER_ROUTE_GPU | taskId=${task.id} | " +
                "reason=hasActionableContent | urgency=${result.urgency} | " +
                "actions=${result.suggestedActions} | summary=${result.summary.take(100)}"
        }
        onProgress(
            "Obsah vyžaduje akci - přesouvám do fronty...",
            mapOf("step" to "route_gpu", "agent" to "simple_qualifier"),
        )
        return RoutingDecision(TaskStateEnum.READY_FOR_GPU, "actionable")
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

    private fun buildMetadata(task: TaskDocument): Map<String, String> {
        return buildMap {
            put("taskId", task.id?.toString() ?: "unknown")
            put("taskType", task.type.name)
            put("correlationId", task.correlationId)
            task.projectId?.let { put("projectId", it.toString()) }
        }
    }
}
