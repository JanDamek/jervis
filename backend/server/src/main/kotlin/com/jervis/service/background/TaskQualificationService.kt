package com.jervis.service.background

import com.jervis.common.client.ITikaClient
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.domain.confluence.ThreadStatusEnum
import com.jervis.domain.email.AliasTypeEnum
import com.jervis.domain.sender.ConversationThread
import com.jervis.domain.task.PendingTask
import com.jervis.dto.PendingTaskState
import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.service.debug.DebugService
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.listener.email.imap.ImapMessage
import com.jervis.service.listener.email.state.EmailMessageStateManager
import com.jervis.service.qualifier.QualifierRuleService
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
import org.jsoup.Jsoup
import org.springframework.stereotype.Service
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * Service for pre-qualifying pending tasks using small, fast models.
 * Filters out obvious spam/noise, delegates everything else to a strong model.
 * Also provides email enrichment with sender profiles and conversation context.
 */
@Service
class TaskQualificationService(
    private val pendingTaskService: PendingTaskService,
    private val senderProfileService: SenderProfileService,
    private val conversationThreadService: ConversationThreadService,
    private val messageLinkService: MessageLinkService,
    private val userTaskService: UserTaskService,
    private val emailMessageStateManager: EmailMessageStateManager,
    private val debugService: DebugService,
    private val tikaClient: ITikaClient,
    private val textNormalizationService: TextNormalizationService,
    private val qualifierRuleService: QualifierRuleService,
    private val llmGateway: LlmGateway,
    private val unsafeLinkPatternRepository: com.jervis.repository.UnsafeLinkPatternMongoRepository,
    private val linkIndexer: com.jervis.service.link.LinkIndexer,
) {
    /**
     * Process the entire Flow of tasks needing qualification.
     * Concurrency is controlled by model-level semaphores (e.g., QUALIFIER model has concurrency: 4).
     * Back-pressure: if the model is at capacity, Flow processing will suspend until a permit is available.
     * Memory safe: doesn't load all 2000+ tasks into a heap at once.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun processAllQualifications() {
        logger.debug { "Starting processAllQualifications - querying DB for tasks..." }
        var processedCount = 0

        try {
            pendingTaskService
                // Include both READY and already QUALIFYING tasks (e.g., reclaimed after restart)
                .findTasksForQualification()
                .flatMapMerge(concurrency = 8) { task ->
                    flow {
                        when (task.state) {
                            PendingTaskState.READY_FOR_QUALIFICATION -> {
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
                            }
                            PendingTaskState.QUALIFYING -> {
                                processedCount++
                                if (processedCount == 1) {
                                    logger.info { "First task received (reclaimed QUALIFYING) - qualification pipeline started" }
                                }
                                if (processedCount % 100 == 0) {
                                    logger.info { "Qualification progress: $processedCount tasks processed..." }
                                }
                                logger.debug { "Reprocessing QUALIFYING task ${task.id} (likely from previous run)" }
                                qualifyTask(task)
                                emit(task)
                            }
                            else -> {
                                // Not eligible in this phase
                                logger.debug { "Skipping task ${task.id} in state ${task.state}" }
                            }
                        }
                    }.catch { e ->
                        logger.error(e) { "Failed to qualify task: ${task.id}" }
                    }
                }.buffer(8)
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

        val senderProfile =
            senderProfileService.findOrCreateProfile(
                identifier = email.from,
                displayName = null,
                aliasType = AliasTypeEnum.EMAIL_WORK,
            )

        val thread =
            conversationThreadService.findOrCreateThread(
                emailHeaders = email,
                senderProfileId = senderProfile.id,
                clientId = clientId,
                projectId = projectId,
            )

        messageLinkService.createLinkFromEmail(
            email = email,
            threadId = thread.id,
            senderProfileId = senderProfile.id,
            ragDocumentId = ragDocumentId,
        )

        senderProfileService.incrementMessagesReceived(senderProfile.id)
        ragDocumentId?.let {
            conversationThreadService.addRagDocumentId(thread.id, it)
        }

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

        val task =
            pendingTaskService.createTask(
                taskType = PendingTaskTypeEnum.EMAIL_PROCESSING,
                content = formatEmailContent(email),
                clientId = clientId,
                projectId = projectId,
            )

        emailMessageStateManager.markMessageIdAsIndexed(accountId, email.messageId)

        logger.info { "Email ${email.messageId} enqueued for qualification and marked as INDEXED" }
        return QualificationResult.Delegate(task = task)
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

        val withoutUrls = URL_PATTERN.replace(plainText, "[URL]")

        return textNormalizationService.normalize(withoutUrls)
    }

    companion object {
        private val URL_PATTERN = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")
    }

    /**
     * Build mapping values for qualifier prompt placeholders from task data.
     * Truncates content to fit within model's context window.
     * Also provides current datetime for past/future event detection.
     * Injects active qualifier rules for the specific task type.
     */
    private suspend fun buildMappingValues(
        task: PendingTask,
        truncatedContent: String,
    ): Map<String, String> =
        buildMap {
            // Truncate content to fit in qualifier context window
            // QUALIFIER model: contextLength=32768, numPredict=128
            // Budget: system prompt (~500 tokens) + response (128) + safety margin (200) = ~828 tokens
            // Available for content: ~32000 tokens ≈ 128000 chars
            val maxContentChars = 128000
            val finalContent =
                if (truncatedContent.length > maxContentChars) {
                    val truncated = truncatedContent.take(maxContentChars)
                    logger.debug { "Content truncated from ${truncatedContent.length} to $maxContentChars chars for task ${task.id}" }
                    truncated + "\n\n[... content truncated for qualifier ...]"
                } else {
                    truncatedContent
                }

            put("content", finalContent)

            val rulesText = qualifierRuleService.getRulesText(task.taskType)
            put("activeQualifierRules", rulesText)

            // Provide current datetime for temporal reasoning
            put("currentDateTime", java.time.Instant.now().toString())
        }

    data class DecisionResponseQualifier(
        val discard: Boolean,
        val reason: String?,
    )

    /**
     * Link-specific qualifier response that includes suggested regex pattern.
     * When LLM identifies UNSAFE link with a pattern, it suggests regex to add to blacklist.
     */
    data class LinkDecisionResponse(
        val discard: Boolean,
        val reason: String?,
        val suggestedRegex: String? = null,
        val patternDescription: String? = null,
    )

    private suspend fun qualifyTask(task: PendingTask) {
        logger.info { "QUALIFICATION_START: id=${task.id} correlationId=${task.correlationId} type=${task.taskType}" }

        debugService.qualificationStart(
            correlationId = task.correlationId,
            taskId = task.id.toHexString(),
            taskType = task.taskType.name,
        )

        // Use link-specific response format for LINK tasks
        when (task.taskType) {
            PendingTaskTypeEnum.LINK_REVIEW, PendingTaskTypeEnum.LINK_SAFETY_REVIEW -> {
                qualifyLinkTask(task)
            }
            else -> {
                qualifyGenericTask(task)
            }
        }
    }

    /**
     * Qualify link-specific tasks with regex pattern suggestion.
     * Links NEVER go to agent - either SAFE (download) or UNSAFE (discard + save pattern).
     */
    private suspend fun qualifyLinkTask(task: PendingTask) {
        val mappingValues = buildMappingValues(task, task.content)
        val decision =
            llmGateway.callLlm(
                task.taskType.promptType,
                LinkDecisionResponse(false, "Link is safe to download", null, null),
                correlationId = task.correlationId,
                mappingValue = mappingValues,
                backgroundMode = true,
            )

        when (decision.result.discard) {
            true -> {
                // UNSAFE - discard link and optionally save pattern
                logger.info {
                    "LINK_QUALIFICATION: id=${task.id} decision=UNSAFE pattern=${decision.result.suggestedRegex}"
                }

                debugService.qualificationDecision(
                    correlationId = task.correlationId,
                    taskId = task.id.toHexString(),
                    decision = "UNSAFE",
                    reason = decision.result.reason ?: "unsafe link",
                )

                // If LLM suggested a regex pattern for blocking, save it
                if (!decision.result.suggestedRegex.isNullOrBlank()) {
                    saveLinkPattern(
                        pattern = decision.result.suggestedRegex,
                        description = decision.result.patternDescription ?: decision.result.reason ?: "UNSAFE link pattern",
                        exampleUrl = extractUrlFromContent(task.content),
                    )
                }

                pendingTaskService.deleteTask(task.id)
            }

            false -> {
                // SAFE - download link and index it
                logger.info {
                    "LINK_QUALIFICATION: id=${task.id} decision=SAFE - indexing link"
                }

                debugService.qualificationDecision(
                    correlationId = task.correlationId,
                    taskId = task.id.toHexString(),
                    decision = "SAFE",
                    reason = decision.result.reason ?: "safe to download",
                )

                // Extract URL from task content
                val url = extractUrlFromContent(task.content)

                try {
                    // Index the link directly (content will be fetched by indexer)
                    linkIndexer.indexLink(
                        url = url,
                        projectId = task.projectId,
                        clientId = task.clientId,
                        content = null, // Let indexer fetch content
                    )
                    logger.info { "Successfully indexed SAFE link: $url" }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to index SAFE link: $url" }
                }

                // Delete task after indexing (success or failure)
                pendingTaskService.deleteTask(task.id)
            }
        }
    }

    /**
     * Qualify generic (non-link) tasks.
     */
    private suspend fun qualifyGenericTask(task: PendingTask) {
        val mappingValues = buildMappingValues(task, task.content)
        val decision =
            llmGateway.callLlm(
                task.taskType.promptType,
                DecisionResponseQualifier(false, "Task busm be passed to AGENT"),
                correlationId = task.correlationId,
                mappingValue = mappingValues,
                backgroundMode = true,
            )

        when (decision.result.discard) {
            true -> {
                logger.info {
                    "QUALIFICATION_DECISION: id=${task.id} correlationId=${task.correlationId} decision=DISCARD reason=spam/noise"
                }

                debugService.qualificationDecision(
                    correlationId = task.correlationId,
                    taskId = task.id.toHexString(),
                    decision = "DISCARD",
                    reason = decision.result.reason ?: "spam/noise",
                )

                pendingTaskService.deleteTask(task.id)
            }

            false -> {
                logger.info {
                    "QUALIFICATION_DECISION: id=${task.id} correlationId=${task.correlationId} decision=DELEGATE reason=actionable"
                }

                debugService.qualificationDecision(
                    correlationId = task.correlationId,
                    taskId = task.id.toHexString(),
                    decision = "DELEGATE",
                    reason = decision.result.reason ?: "actionable",
                )

                pendingTaskService.updateState(
                    task.id,
                    PendingTaskState.QUALIFYING,
                    PendingTaskState.DISPATCHED_GPU,
                )
            }
        }
    }

    /**
     * Save suggested regex pattern to MongoDB for future link blocking.
     */
    private suspend fun saveLinkPattern(
        pattern: String,
        description: String,
        exampleUrl: String,
    ) {
        try {
            // Check if pattern already exists
            val existing = unsafeLinkPatternRepository.findByPattern(pattern)
            if (existing != null) {
                logger.debug { "Pattern already exists: $pattern" }
                return
            }

            // Validate regex pattern
            try {
                Regex(pattern)
            } catch (e: Exception) {
                logger.warn { "Invalid regex pattern suggested by LLM: $pattern - ${e.message}" }
                return
            }

            val document =
                com.jervis.entity.UnsafeLinkPatternDocument(
                    pattern = pattern,
                    description = description,
                    exampleUrl = exampleUrl,
                    matchCount = 1,
                    enabled = true,
                )

            unsafeLinkPatternRepository.save(document)
            logger.info { "Saved new UNSAFE link pattern: $pattern (${description})" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save link pattern: $pattern" }
        }
    }

    /**
     * Extract URL from task content (format varies by task type).
     */
    private fun extractUrlFromContent(content: String): String {
        // Try to extract URL from content - line starting with "URL: "
        val urlLineRegex = Regex("""URL:\s*(https?://[^\s<>"{}|\\^`\[\]]+)""")
        urlLineRegex.find(content)?.let { match ->
            return match.groupValues[1]
        }

        // Fallback: find any URL in content
        val urlRegex = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")
        val match = urlRegex.find(content)
        return match?.value ?: content.take(200)
    }


    private fun looksLikeResolution(email: ImapMessage): Boolean {
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

        return if (looksLikeResolution(email)) {
            val closed = active.map { userTaskService.completeTask(it.id, resolutionSourceUri).id.toHexString() }
            true to closed
        } else {
            false to emptyList()
        }
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
