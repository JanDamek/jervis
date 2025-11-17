package com.jervis.service.background

import com.jervis.common.client.ITikaClient
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.domain.confluence.ThreadStatusEnum
import com.jervis.domain.email.AliasTypeEnum
import com.jervis.domain.sender.ConversationThread
import com.jervis.domain.task.PendingTask
import com.jervis.domain.task.PendingTaskTypeEnum
import com.jervis.service.gateway.QualifierLlmGateway
import com.jervis.service.listener.email.imap.ImapMessage
import com.jervis.service.sender.ConversationThreadService
import com.jervis.service.sender.MessageLinkService
import com.jervis.service.sender.SenderProfileService
import com.jervis.service.task.UserTaskService
import com.jervis.service.text.TextNormalizationService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.util.Base64
import org.jsoup.Jsoup

private val logger = KotlinLogging.logger {}

/**
 * Service for pre-qualifying pending tasks using small, fast models.
 * Filters out obvious spam/noise, delegates everything else to strong model.
 * Also provides email enrichment with sender profiles and conversation context.
 */
@Service
class TaskQualificationService(
    private val qualifierGateway: QualifierLlmGateway,
    private val pendingTaskService: PendingTaskService,
    private val backgroundTaskGoalsService: BackgroundTaskGoalsService,
    private val senderProfileService: SenderProfileService,
    private val conversationThreadService: ConversationThreadService,
    private val messageLinkService: MessageLinkService,
    private val userTaskService: UserTaskService,
    private val emailMessageStateManager: com.jervis.service.listener.email.state.EmailMessageStateManager,
    private val debugService: com.jervis.service.debug.DebugService,
    private val tikaClient: ITikaClient,
    private val textNormalizationService: TextNormalizationService,
) {
    /**
     * Process entire Flow of tasks needing qualification.
     * Concurrency is controlled by model-level semaphores (e.g., QUALIFIER model has concurrency: 4).
     * Back-pressure: if model is at capacity, Flow processing will suspend until permit is available.
     * Memory safe: doesn't load all 2000+ tasks into heap at once.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun processAllQualifications() {
        logger.debug { "Starting processAllQualifications - querying DB for tasks..." }
        var processedCount = 0

        try {
            pendingTaskService
                .findTasksReadyForQualification()
                .flatMapMerge(concurrency = 32) { task ->
                    flow {
                        val claimed = pendingTaskService.tryClaimForQualification(task.id)
                        if (claimed != null) {
                            processedCount++
                            if (processedCount == 1) {
                                logger.info { "First task received - qualification pipeline started" }
                            }
                            if (processedCount % 100 == 0) {
                                logger.info { "Qualification progress: $processedCount tasks processed..." }
                            }
                            qualifyTask(claimed)
                            emit(claimed)
                        } else {
                            logger.debug { "Task ${task.id} could not be claimed (race), skipping" }
                        }
                    }.catch { e ->
                        logger.error(e) { "Failed to qualify task: ${task.id}" }
                    }
                }.buffer(32)
                .collect { }

            if (processedCount > 0) {
                logger.info { "Qualification batch complete: $processedCount total tasks processed" }
            } else {
                logger.debug { "No tasks found needing qualification" }
            }
        } catch (e: Exception) {
            logger.error(e) { "ERROR in processAllQualifications after $processedCount tasks" }
            throw e
        }
    }

    suspend fun qualifyEmailEnriched(
        email: ImapMessage,
        accountId: ObjectId,
        clientId: ObjectId,
        projectId: ObjectId?,
        ragDocumentId: String?,
    ): QualificationResult {
        logger.info { "Qualifying inbound email: ${email.messageId}" }

        // 1. Create/find sender profile
        val senderProfile =
            senderProfileService.findOrCreateProfile(
                identifier = email.from,
                displayName = null,
                aliasType = AliasTypeEnum.EMAIL_WORK,
            )

        // 2. Create/find thread
        val thread =
            conversationThreadService.findOrCreateThread(
                emailHeaders = email,
                senderProfileId = senderProfile.id,
                clientId = clientId,
                projectId = projectId,
            )

        // 3. Create message link
        messageLinkService.createLinkFromEmail(
            email = email,
            threadId = thread.id,
            senderProfileId = senderProfile.id,
            ragDocumentId = ragDocumentId,
        )

        // 4. Update counters
        senderProfileService.incrementMessagesReceived(senderProfile.id)
        ragDocumentId?.let {
            conversationThreadService.addRagDocumentId(thread.id, it)
        }

        // 4.1 Resolution hook BEFORE creating any new task
        val sourceUri = "email://${accountId.toHexString()}/${email.messageId}"
        val (resolved, closedTaskIds) = resolveExistingUserTasksIfApplicable(thread, clientId, sourceUri, email)
        if (resolved) {
            conversationThreadService.setRequiresResponse(thread.id, false)
            if (thread.actionItems.all { it.completed }) {
                conversationThreadService.updateStatus(thread.id, ThreadStatusEnum.RESOLVED)
            }
            logger.info {
                "Thread ${thread.threadId} resolved by inbound email ${email.messageId}; closed tasks: ${closedTaskIds.joinToString()}"
            }
            return QualificationResult.Discard(reason = "Resolved by inbound update; closed tasks: ${closedTaskIds.joinToString()}")
        }

        // 5. Create pending task without any LLM calls – fast path during indexing.
        //    The actual qualification (DISCARD/DELEGATE) will be performed later by background engine.

        val task =
            pendingTaskService.createTask(
                taskType = PendingTaskTypeEnum.EMAIL_PROCESSING,
                content = formatEmailContent(email),
                clientId = clientId,
                projectId = projectId,
            )

        // Requirement: writing pending task must atomically flip email state to INDEXED
        emailMessageStateManager.markMessageIdAsIndexed(accountId, email.messageId)

        logger.info { "Email ${email.messageId} enqueued for qualification and marked as INDEXED" }
        return QualificationResult.Delegate(task = task)
    }

    private suspend fun qualifyBasicEmail(
        email: ImapMessage,
        clientId: ObjectId,
        correlationId: String,
    ): EmailDecision {
        val cleanSnippet = cleanEmailBody(email.content).take(1000)
        val emailContent =
            buildString {
                appendLine("FROM: ${email.from}")
                appendLine("SUBJECT: ${email.subject}")
                appendLine("DATE: ${email.receivedAt}")
                appendLine()
                appendLine(cleanSnippet)
            }

        return try {
            val taskConfig = backgroundTaskGoalsService.getQualifierPrompts(PendingTaskTypeEnum.EMAIL_PROCESSING)
            val systemPrompt = taskConfig.qualifierSystemPrompt ?: ""
            val userPromptTemplate = taskConfig.qualifierUserPrompt ?: ""

            val decision = qualifierGateway.qualify(systemPrompt, userPromptTemplate, mapOf("content" to emailContent), correlationId)
            when (decision) {
                is QualifierLlmGateway.QualifierDecision.Discard -> EmailDecision.DISCARD
                is QualifierLlmGateway.QualifierDecision.Delegate -> EmailDecision.DELEGATE
            }
        } catch (e: Exception) {
            logger.warn(e) { "Qualifier failed, defaulting to DELEGATE: ${e.message}" }
            EmailDecision.DELEGATE
        }
    }

    private suspend fun formatEmailContent(email: ImapMessage): String =
        buildString {
            appendLine("FROM: ${email.from}")
            appendLine("TO: ${email.to}")
            appendLine("SUBJECT: ${email.subject}")
            appendLine("DATE: ${email.receivedAt}")
            appendLine()
            appendLine("CONTENT:")
            appendLine(cleanEmailBody(email.content))
            if (email.attachments.isNotEmpty()) {
                appendLine()
                appendLine("ATTACHMENTS:")
                email.attachments.forEach { att ->
                    appendLine("  - ${att.fileName} (${att.contentType})")
                }
            }
        }

    /**
     * Clean email body for PendingTask consumption:
     * - Convert HTML/other formats to plain text using Tika
     * - Remove/neutralize URLs (links are indexed separately by LinkIndexer)
     * - Normalize whitespace and control characters
     */
    private suspend fun cleanEmailBody(rawContent: String): String {
        val plainText =
            try {
                val bytes = rawContent.toByteArray(Charsets.UTF_8)
                val res =
                    tikaClient.process(
                        TikaProcessRequest(
                            source =
                                TikaProcessRequest.Source.FileBytes(
                                    fileName = "email-body.html",
                                    dataBase64 = Base64.getEncoder().encodeToString(bytes),
                                ),
                            includeMetadata = false,
                        ),
                    )
                if (res.success && res.plainText.isNotBlank()) res.plainText else Jsoup.parse(rawContent).text()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse email body with Tika, using Jsoup fallback" }
                try {
                    Jsoup.parse(rawContent).text()
                } catch (_: Exception) {
                    rawContent
                }
            }

        // Remove URLs to avoid noisy anchors/signatures; actual link content is indexed elsewhere
        val withoutUrls = URL_PATTERN.replace(plainText, "[URL]")

        return textNormalizationService.normalize(withoutUrls)
    }

    companion object {
        private val URL_PATTERN = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")
    }

    /**
     * Build mapping values for qualifier prompt placeholders from task data.
     * All information is now in content field - just pass it through.
     */
    private fun buildMappingValues(
        task: PendingTask,
        truncatedContent: String,
    ): Map<String, String> =
        buildMap {
            // Only provide values we truly have. Do not invent.
            put("content", truncatedContent)
        }

    private suspend fun qualifyTask(task: PendingTask) {
        val startTime = System.currentTimeMillis()
        logger.info { "QUALIFICATION_START: id=${task.id} correlationId=${task.correlationId} type=${task.taskType}" }

        // Publish debug event
        debugService.qualificationStart(
            correlationId = task.correlationId,
            taskId = task.id.toHexString(),
            taskType = task.taskType.name
        )

        val taskConfig = backgroundTaskGoalsService.getQualifierPrompts(task.taskType)

        val systemPrompt = taskConfig.qualifierSystemPrompt ?: ""
        val userPromptTemplate = taskConfig.qualifierUserPrompt ?: ""

        try {
            val maxContentChars = 20_000
            val rawContent = task.content
            val truncatedContent =
                if (rawContent.length > maxContentChars) {
                    val truncated = rawContent.take(maxContentChars)
                    logger.debug {
                        "Task ${task.id} content truncated from ${rawContent.length} to $maxContentChars chars for qualification"
                    }
                    truncated + "\n\n[... content truncated for qualification ...]"
                } else {
                    rawContent
                }

            val mappingValues = buildMappingValues(task, truncatedContent)

            val decision =
                qualifierGateway.qualify(
                    systemPromptTemplate = systemPrompt,
                    userPromptTemplate = userPromptTemplate,
                    mappingValues = mappingValues,
                    correlationId = task.correlationId,
                )

            val duration = System.currentTimeMillis() - startTime

            when (decision) {
                is QualifierLlmGateway.QualifierDecision.Discard -> {
                    logger.info { "QUALIFICATION_DECISION: id=${task.id} correlationId=${task.correlationId} decision=DISCARD duration=${duration}ms reason=spam/noise" }

                    // Publish debug event
                    debugService.qualificationDecision(
                        correlationId = task.correlationId,
                        taskId = task.id.toHexString(),
                        decision = "DISCARD",
                        duration = duration,
                        reason = "spam/noise"
                    )

                    pendingTaskService.deleteTask(task.id)
                }

                is QualifierLlmGateway.QualifierDecision.Delegate -> {
                    logger.info { "QUALIFICATION_DECISION: id=${task.id} correlationId=${task.correlationId} decision=DELEGATE duration=${duration}ms reason=actionable" }

                    // Publish debug event
                    debugService.qualificationDecision(
                        correlationId = task.correlationId,
                        taskId = task.id.toHexString(),
                        decision = "DELEGATE",
                        duration = duration,
                        reason = "actionable"
                    )

                    pendingTaskService.updateState(
                        task.id,
                        com.jervis.domain.task.PendingTaskState.QUALIFYING,
                        com.jervis.domain.task.PendingTaskState.DISPATCHED_GPU,
                    )
                }
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error(e) { "QUALIFICATION_ERROR: id=${task.id} correlationId=${task.correlationId} duration=${duration}ms error=${e.message} - delegating to GPU" }
            // Even if qualifier failed, delegate to strong model by transitioning state
            try {
                pendingTaskService.updateState(
                    task.id,
                    com.jervis.domain.task.PendingTaskState.QUALIFYING,
                    com.jervis.domain.task.PendingTaskState.DISPATCHED_GPU,
                )
            } catch (ie: Exception) {
                logger.warn(ie) { "QUALIFICATION_FALLBACK_FAILED: id=${task.id} error=${ie.message}" }
            }
        }
    }

    private fun looksLikeResolution(
        email: ImapMessage,
        thread: ConversationThread,
    ): Boolean {
        val text = (email.subject + "\n" + email.content).lowercase()
        val resolutionMarkers =
            listOf(
                "approved",
                "confirm",
                "confirmed",
                "done",
                "resolved",
                "solved",
                "fixed",
                "thanks",
                "thank you",
                "attached",
                "see attachment",
                "see attached",
            )
        val containsMarker = resolutionMarkers.any { it in text }
        val hasQuestion = text.contains("?")
        return containsMarker && !hasQuestion
    }

    private suspend fun resolveExistingUserTasksIfApplicable(
        thread: ConversationThread,
        clientId: ObjectId,
        resolutionSourceUri: String,
        email: ImapMessage,
    ): Pair<Boolean, List<String>> {
        val active = userTaskService.findActiveTasksByThread(clientId, thread.id).toList()
        if (active.isEmpty()) return false to emptyList()

        return if (looksLikeResolution(email, thread)) {
            val closed = active.map { userTaskService.completeTask(it.id, resolutionSourceUri).id.toHexString() }
            true to closed
        } else {
            false to emptyList()
        }
    }

    enum class EmailDecision {
        DISCARD,
        DELEGATE,
    }

    sealed class QualificationResult {
        data class Discard(
            val reason: String,
        ) : QualificationResult()

        data class Delegate(
            val task: PendingTask,
        ) : QualificationResult()
    }
}
