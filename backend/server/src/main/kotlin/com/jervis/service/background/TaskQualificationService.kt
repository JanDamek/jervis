package com.jervis.service.background

import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.entity.TaskDocument
import com.jervis.knowledgebase.model.Attachment
import com.jervis.knowledgebase.model.FullIngestRequest
import com.jervis.repository.ProjectRepository
import com.jervis.service.CloudModelPolicyResolver
import com.jervis.service.text.TikaTextExtractionService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File
import java.time.Instant

/**
 * Indexing service — dispatches INDEXING tasks to KB microservice for async processing.
 *
 * Flow (fire-and-forget):
 * 1. Claim INDEXING tasks (atomic via indexingClaimedAt, state stays INDEXING)
 * 2. Extract text + load attachments (local, fast)
 * 3. Submit to KB's /ingest/full/async endpoint (returns immediately with HTTP 202)
 * 4. Move on to next task — KB processes in background
 * 5. KB calls /internal/kb-done when finished → routes INDEXING → QUEUED or DONE
 *
 * Error handling:
 * - If KB is unreachable or rejects the request → release claim with backoff
 * - KB handles its own retry logic internally (Ollama busy, timeouts, etc.)
 * - When KB permanently fails, it calls /internal/kb-done with status="error"
 */
@Service
class TaskQualificationService(
    private val taskService: TaskService,
    private val knowledgeClient: com.jervis.configuration.KnowledgeServiceRestClient,
    private val tikaTextExtractionService: TikaTextExtractionService,
    private val projectRepository: ProjectRepository,
    private val cloudModelPolicyResolver: CloudModelPolicyResolver,
    private val notificationRpc: com.jervis.rpc.NotificationRpcImpl,
) {
    private val logger = KotlinLogging.logger {}

    private val isQualificationRunning =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun processAllQualifications() {
        if (!isQualificationRunning.compareAndSet(false, true)) {
            logger.debug { "INDEXING_SKIPPED: Another cycle already running" }
            return
        }

        try {
            logger.debug { "INDEXING_CYCLE_START" }

            var dispatchedCount = 0

            taskService
                .findTasksForIndexing()
                .buffer(1)
                .flatMapMerge(concurrency = 1) { task ->
                    flow {
                        runCatching { processOne(task) }
                            .onFailure { e ->
                                try {
                                    logger.error(e) {
                                        "INDEXING_DISPATCH_ERROR: task=${task.id} type=${task.type} msg=${e.message}"
                                    }
                                    // KB is unreachable or rejected the request → release claim with backoff
                                    taskService.returnToIndexingQueue(task)
                                } catch (inner: Exception) {
                                    logger.error(inner) {
                                        "INDEXING_ERROR_HANDLER_FAILED: task=${task.id} — stuck until stale claim recovery"
                                    }
                                }
                            }
                        emit(Unit)
                    }
                }.onEach { dispatchedCount++ }
                .catch { e ->
                    logger.error(e) { "Indexing stream failure: ${e.message}" }
                }.collect()

            // Recover stale claims and ERROR indexing tasks
            taskService.recoverStuckIndexingTasks()

            if (dispatchedCount > 0) {
                logger.info { "INDEXING_CYCLE_COMPLETE: dispatched=$dispatchedCount" }
            }
        } finally {
            isQualificationRunning.set(false)
        }
    }

    private suspend fun processOne(original: TaskDocument) {
        val task =
            taskService.claimForIndexing(original) ?: run {
                logger.debug { "INDEXING_SKIP: id=${original.id} - task already claimed" }
                return
            }

        logger.info { "INDEXING_DISPATCH_START | taskId=${task.id} | type=${task.type} | correlationId=${task.correlationId}" }

        emitProgress(task, "Zahajuji indexaci...", "agent_start")

        // 1. Extract clean text from content
        val cleanedContent = tikaTextExtractionService.extractPlainText(
            content = task.content,
            fileName = "task-${task.correlationId}.txt",
        )
        emitProgress(task, "Text extrahován (${cleanedContent.length} znaků)", "text_extracted")

        // 2. Load attachments from storage
        val attachments = loadAttachments(task)
        if (attachments.isNotEmpty()) {
            emitProgress(task, "Načteno ${attachments.size} příloh", "attachments_loaded")
        }

        // 3. Build request and submit to KB (fire-and-forget)
        emitProgress(task, "Odesílám do KB služby...", "kb_call_start")
        val groupId = task.projectId?.let { pid ->
            try { projectRepository.getById(pid)?.groupId?.toString() } catch (_: Exception) { null }
        }
        val request = FullIngestRequest(
            clientId = task.clientId,
            projectId = task.projectId,
            groupId = groupId,
            sourceUrn = task.correlationId,
            sourceType = task.type,
            subject = task.content.lineSequence().firstOrNull()?.take(200),
            content = cleanedContent,
            metadata = buildMap {
                put("taskId", task.id?.toString() ?: "unknown")
                put("taskType", task.type.name)
                put("correlationId", task.correlationId)
                task.projectId?.let { put("projectId", it.toString()) }
            },
            observedAt = Instant.now(),
            attachments = attachments,
        )

        // Priority: MCP/chat tasks get CRITICAL (1), regular indexing gets 4
        val kbPriority = when {
            task.sourceUrn.value.startsWith("agent://mcp") -> 1
            task.sourceUrn.value.startsWith("chat://") -> 1
            else -> 4
        }

        // Resolve OpenRouter tier for KB write LLM routing
        val policy = cloudModelPolicyResolver.resolve(task.clientId, task.projectId)
        val maxTier = policy.maxOpenRouterTier.name

        val accepted = knowledgeClient.submitFullIngestAsync(
            request,
            taskId = task.id?.toString() ?: "",
            clientId = task.clientId.toString(),
            priority = kbPriority,
            maxTier = maxTier,
        )

        if (!accepted) {
            throw RuntimeException("KB rejected async ingest request for task ${task.id}")
        }

        emitProgress(task, "Předáno KB — zpracovává se na pozadí", "kb_accepted")

        logger.info { "INDEXING_DISPATCHED: id=${task.id} type=${task.type}" }
    }

    private fun loadAttachments(task: TaskDocument): List<Attachment> {
        if (task.attachments.isEmpty()) return emptyList()

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

    private suspend fun emitProgress(task: TaskDocument, message: String, step: String) {
        taskService.appendQualificationStep(
            task.id,
            com.jervis.entity.QualificationStepRecord(
                timestamp = Instant.now(),
                step = step,
                message = message,
                metadata = mapOf("step" to step),
            ),
        )
        notificationRpc.emitQualificationProgress(
            taskId = task.id.toString(),
            clientId = task.clientId.toString(),
            message = message,
            step = step,
            metadata = mapOf("step" to step),
        )
    }
}
