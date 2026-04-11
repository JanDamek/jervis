package com.jervis.task

import com.jervis.infrastructure.llm.KnowledgeServiceRestClient
import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.task.TaskDocument
import com.jervis.knowledgebase.model.Attachment
import com.jervis.knowledgebase.model.FullIngestRequest
import com.jervis.project.ProjectRepository
import com.jervis.infrastructure.llm.CloudModelPolicyResolver

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import mu.KotlinLogging
import org.bson.types.ObjectId
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
    private val taskRepository: TaskRepository,
    private val knowledgeClient: com.jervis.infrastructure.llm.KnowledgeServiceRestClient,
    private val projectRepository: ProjectRepository,
    private val cloudModelPolicyResolver: CloudModelPolicyResolver,
    private val notificationRpc: com.jervis.rpc.NotificationRpcImpl,
    private val emailMessageIndexRepository: com.jervis.email.EmailMessageIndexRepository,
    private val pythonOrchestratorClient: com.jervis.agent.PythonOrchestratorClient,
) {
    private val logger = KotlinLogging.logger {}

    private val isQualificationRunning =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /**
     * Phase 3 re-entrant qualifier loop entry point.
     *
     * Scans for tasks with `needsQualification=true` and dispatches each to the
     * Python `/qualify` endpoint. Python's response arrives asynchronously via
     * `/internal/qualification-done`, where Kotlin clears the flag and applies
     * the new decision (DONE / QUEUED / URGENT_ALERT / ESCALATE / DECOMPOSE).
     *
     * Triggered by lifecycle events:
     *  - new INDEXING task created (via KB-done callback path)
     *  - all children of a BLOCKED parent reach DONE → parent unblocks
     *  - user responds to a USER_TASK and resumes the task
     */
    suspend fun requalifyPendingTasks() {
        var dispatched = 0
        var skipped = 0
        try {
            taskRepository.findByNeedsQualificationTrueOrderByCreatedAtAsc().collect { task ->
                try {
                    val request = com.jervis.agent.QualifyRequestDto(
                        taskId = task.id.toString(),
                        clientId = task.clientId.toString(),
                        projectId = task.projectId?.toString(),
                        sourceUrn = task.sourceUrn.value,
                        summary = task.kbSummary?.take(2000) ?: task.content.take(2000),
                        entities = task.kbEntities,
                        suggestedActions = emptyList(),
                        urgency = "normal",
                        actionType = task.actionType,
                        estimatedComplexity = task.estimatedComplexity,
                        isAssignedToMe = false,
                        hasFutureDeadline = task.scheduledAt != null,
                        suggestedDeadline = task.scheduledAt?.toString(),
                        suggestedAgent = null,
                        affectedFiles = emptyList(),
                        relatedKbNodes = task.kbEntities,
                        hasAttachments = task.hasAttachments,
                        attachmentCount = task.attachmentCount,
                        attachments = emptyList(),
                        content = task.content.take(3000),
                        mentionsJervis = task.mentionsJervis,
                    )
                    val response = pythonOrchestratorClient.qualify(request)
                    if (response != null) {
                        dispatched++
                    } else {
                        // Circuit-breaker open or HTTP error → leave the flag set,
                        // RequalificationLoop will retry on its next tick.
                        skipped++
                        logger.debug { "REQUALIFY_SKIPPED: taskId=${task.id} (qualify endpoint unavailable)" }
                    }
                } catch (e: Exception) {
                    skipped++
                    logger.warn(e) { "REQUALIFY_DISPATCH_FAILED: taskId=${task.id}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "REQUALIFY_LOOP_ERROR: ${e.message}" }
        }
        if (dispatched > 0 || skipped > 0) {
            logger.info { "REQUALIFY_CYCLE_COMPLETE: dispatched=$dispatched skipped=$skipped" }
        }
    }

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

        // Content already cleaned at TaskService.createTask() time via document-extraction service
        val cleanedContent = task.content
        emitProgress(task, "Text připraven (${cleanedContent.length} znaků)", "text_extracted")

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
        val urnScheme = task.sourceUrn.scheme()
        val request = FullIngestRequest(
            clientId = task.clientId,
            projectId = task.projectId,
            groupId = groupId,
            sourceUrn = task.correlationId,
            sourceType = task.sourceUrn.kbSourceType(),
            subject = task.content.lineSequence().firstOrNull()?.take(200),
            content = cleanedContent,
            metadata = buildMap {
                put("taskId", task.id?.toString() ?: "unknown")
                put("taskType", task.type.name)
                put("sourceScheme", urnScheme)
                put("correlationId", task.correlationId)
                task.projectId?.let { put("projectId", it.toString()) }
                // Email threading metadata — enables REPLY_TO edges in KB graph
                if (urnScheme == "email") {
                    try {
                        // correlationId format: "email:{emailDocId}"
                        val emailId = task.correlationId.removePrefix("email:")
                        val emailDoc = try { emailMessageIndexRepository.findById(ObjectId(emailId)) } catch (_: Exception) { null }
                        if (emailDoc != null) {
                            emailDoc.threadId?.let { put("emailThreadId", it) }
                            emailDoc.messageId?.let { put("emailMessageId", it) }
                            emailDoc.inReplyTo?.let { put("emailInReplyTo", it) }
                            if (emailDoc.references.isNotEmpty()) {
                                put("emailReferences", emailDoc.references.joinToString(","))
                            }
                            emailDoc.from?.let { put("emailFrom", it) }
                            emailDoc.subject?.let { put("emailSubject", it) }
                        }
                    } catch (e: Exception) {
                        logger.debug { "Could not load email metadata for task ${task.id}: ${e.message}" }
                    }
                }
                // Meeting metadata — enables meeting-specific graph nodes and search in KB
                if (urnScheme == "meeting") {
                    // correlationId format: "meeting:{meetingObjectId}"
                    val meetingId = task.correlationId.removePrefix("meeting:")
                    put("meetingId", meetingId)
                    // Extract title from first line of content (format: "# Title")
                    val firstLine = task.content.lineSequence().firstOrNull()?.removePrefix("# ")?.trim()
                    if (!firstLine.isNullOrBlank()) {
                        put("meetingTitle", firstLine)
                    }
                    // Extract meeting type from content (format: "**Type:** ENUM_NAME")
                    val typeLine = task.content.lineSequence()
                        .firstOrNull { it.startsWith("**Type:**") }
                        ?.removePrefix("**Type:**")?.trim()
                    if (!typeLine.isNullOrBlank()) {
                        put("meetingType", typeLine)
                    }
                    // Extract participants from content (format: "**Participants:** Name1, Name2")
                    val participantsLine = task.content.lineSequence()
                        .firstOrNull { it.startsWith("**Participants:**") }
                        ?.removePrefix("**Participants:**")?.trim()
                    if (!participantsLine.isNullOrBlank()) {
                        put("meetingParticipants", participantsLine)
                    }
                    // Check if this is an unclassified meeting (sentinel clientId)
                    if (task.clientId == com.jervis.common.types.ClientId.UNCLASSIFIED) {
                        put("unclassified", "true")
                    }
                }
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
            com.jervis.task.QualificationStepRecord(
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
