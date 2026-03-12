package com.jervis.qualifier

import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.entity.TaskDocument
import com.jervis.knowledgebase.model.Attachment
import com.jervis.knowledgebase.model.FullIngestRequest
import com.jervis.repository.ProjectRepository
import com.jervis.service.CloudModelPolicyResolver
import com.jervis.service.background.TaskService
import com.jervis.service.text.TikaTextExtractionService
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File
import java.time.Instant

/**
 * Qualification agent that dispatches tasks to KB microservice asynchronously.
 *
 * Fire-and-forget flow:
 * 1. Extract text from task content (Tika)
 * 2. Read attachment files from storage
 * 3. Submit to KB's async endpoint (returns immediately)
 * 4. KB processes in background (RAG, summary, graph extraction)
 * 5. KB calls /internal/kb-done when finished → KbResultRouter handles routing
 *
 * The server qualification worker is NOT blocked — it can dispatch multiple tasks
 * in rapid succession. KB manages its own queue, retry, and timing.
 */
@Service
class SimpleQualifierAgent(
    private val knowledgeClient: com.jervis.configuration.KnowledgeServiceRestClient,
    private val taskService: TaskService,
    private val tikaTextExtractionService: TikaTextExtractionService,
    private val projectRepository: ProjectRepository,
    private val cloudModelPolicyResolver: CloudModelPolicyResolver,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Dispatch task to KB for async processing.
     *
     * Returns immediately after KB accepts the request.
     * Task routing happens later in the /internal/kb-done callback.
     *
     * @throws RuntimeException if KB rejects the request or is unreachable
     */
    suspend fun dispatch(
        task: TaskDocument,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit = { _, _ -> },
    ) {
        logger.info { "QUALIFIER_DISPATCH_START | taskId=${task.id} | type=${task.type} | correlationId=${task.correlationId}" }

        onProgress("Zahajuji kvalifikaci...", mapOf("step" to "agent_start", "agent" to "simple_qualifier"))

        // 1. Extract clean text from content
        val cleanedContent = tikaTextExtractionService.extractPlainText(
            content = task.content,
            fileName = "task-${task.correlationId}.txt",
        )
        onProgress("Text extrahován (${cleanedContent.length} znaků)", mapOf("step" to "text_extracted", "agent" to "simple_qualifier", "chars" to cleanedContent.length.toString()))

        // 2. Load attachments from storage
        val attachments = loadAttachments(task)
        logger.info { "QUALIFIER_DISPATCH_ATTACHMENTS | taskId=${task.id} | count=${attachments.size}" }
        if (attachments.isNotEmpty()) {
            onProgress("Načteno ${attachments.size} příloh", mapOf("step" to "attachments_loaded", "agent" to "simple_qualifier", "count" to attachments.size.toString()))
        }

        // 3. Build request and submit to KB (fire-and-forget)
        onProgress("Odesílám do KB služby...", mapOf("step" to "kb_call_start", "agent" to "simple_qualifier"))
        val groupId = task.projectId?.let { pid ->
            try { projectRepository.getById(pid)?.groupId?.toString() } catch (_: Exception) { null }
        }
        val request = FullIngestRequest(
            clientId = task.clientId,
            projectId = task.projectId,
            groupId = groupId,
            sourceUrn = task.correlationId,
            sourceType = task.type,
            subject = extractSubject(task),
            content = cleanedContent,
            metadata = buildMetadata(task),
            observedAt = Instant.now(),
            attachments = attachments,
        )

        // Derive KB processing priority from task source:
        // MCP-submitted and chat-created tasks get CRITICAL priority (1) to preempt regular indexing queue
        val kbPriority = when {
            task.sourceUrn.value.startsWith("agent://mcp") -> 1
            task.sourceUrn.value.startsWith("chat://") -> 1
            else -> 4  // regular indexing (email, webhook, etc.)
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

        onProgress("Předáno KB — zpracovává se na pozadí", mapOf("step" to "kb_accepted", "agent" to "simple_qualifier"))

        logger.info { "QUALIFIER_DISPATCH_DONE | taskId=${task.id} | KB accepted async request" }
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

    private fun extractSubject(task: TaskDocument): String? {
        return task.content.lineSequence().firstOrNull()?.take(200)
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
