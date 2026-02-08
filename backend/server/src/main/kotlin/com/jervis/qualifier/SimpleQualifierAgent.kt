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
import com.jervis.service.storage.DirectoryStructureService
import com.jervis.service.text.TikaTextExtractionService
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File
import java.time.Instant

/**
 * Simplified qualification agent that delegates all heavy lifting to KB microservice.
 *
 * Flow:
 * 1. Extract text from task content (Tika)
 * 2. Read attachment files from storage
 * 3. Call KB's ingestFull endpoint (handles vision/OCR, RAG, graph, summary generation)
 * 4. Route based on hasActionableContent flag:
 *    - true → READY_FOR_GPU (needs user attention/action)
 *    - false → DONE (just indexed, no action needed)
 */
@Service
class SimpleQualifierAgent(
    private val knowledgeService: KnowledgeService,
    private val taskService: TaskService,
    private val tikaTextExtractionService: TikaTextExtractionService,
    private val directoryStructureService: DirectoryStructureService,
) {
    private val logger = KotlinLogging.logger {}

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

            // 5. Route based on actionability
            val newState = if (result.hasActionableContent) {
                // Content requires user action → send to GPU for orchestration
                logger.info {
                    "SIMPLE_QUALIFIER_ROUTE_GPU | taskId=${task.id} | " +
                        "reason=hasActionableContent | actions=${result.suggestedActions} | " +
                        "summary=${result.summary.take(100)}"
                }
                onProgress(
                    "Obsah vyžaduje akci - přesouvám do fronty...",
                    mapOf("step" to "route_gpu", "agent" to "simple_qualifier"),
                )
                TaskStateEnum.READY_FOR_GPU
            } else {
                // Content is just informational → mark as dispatched (processed, no action needed)
                logger.info {
                    "SIMPLE_QUALIFIER_ROUTE_DISPATCHED | taskId=${task.id} | " +
                        "reason=noActionRequired | summary=${result.summary.take(100)}"
                }
                onProgress("Hotovo - obsah zaindexován", mapOf("step" to "done", "agent" to "simple_qualifier"))
                TaskStateEnum.DISPATCHED_GPU
            }

            taskService.updateState(task, newState)

            logger.info {
                "SIMPLE_QUALIFIER_COMPLETE | taskId=${task.id} | " +
                    "finalState=$newState | summary=${result.summary.take(100)}"
            }

            return result.summary
        } catch (e: Exception) {
            logger.error(e) { "SIMPLE_QUALIFIER_ERROR | taskId=${task.id} | error=${e.message}" }
            throw e
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
