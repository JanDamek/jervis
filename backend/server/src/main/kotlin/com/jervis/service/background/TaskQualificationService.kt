package com.jervis.service.background

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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

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

        // 5. Qualify: discard/delegate
        val decision = qualifyBasicEmail(email, clientId)

        if (decision == EmailDecision.DISCARD) {
            logger.info { "Email ${email.messageId} discarded by qualifier" }
            return QualificationResult.Discard(reason = "Routine message")
        }

        // 6. Create task – all details must be in content, no enrichment/context maps

        val task =
            pendingTaskService.createTask(
                taskType = PendingTaskTypeEnum.EMAIL_PROCESSING,
                content = formatEmailContent(email),
                clientId = clientId,
                projectId = projectId,
            )

        // Requirement: writing pending task must atomically flip email state to INDEXED
        emailMessageStateManager.markMessageIdAsIndexed(accountId, email.messageId)

        logger.info { "Email ${email.messageId} delegated to planner and marked as INDEXED" }
        return QualificationResult.Delegate(task = task)
    }

    private suspend fun qualifyBasicEmail(
        email: ImapMessage,
        clientId: ObjectId,
    ): EmailDecision {
        val emailContent =
            buildString {
                appendLine("FROM: ${email.from}")
                appendLine("SUBJECT: ${email.subject}")
                appendLine("DATE: ${email.receivedAt}")
                appendLine()
                appendLine(email.content.take(1000))
            }

        return try {
            val taskConfig = backgroundTaskGoalsService.getQualifierPrompts(PendingTaskTypeEnum.EMAIL_PROCESSING)
            val systemPrompt = taskConfig.qualifierSystemPrompt ?: ""
            val userPromptTemplate = taskConfig.qualifierUserPrompt ?: ""

            val decision = qualifierGateway.qualify(systemPrompt, userPromptTemplate, mapOf("content" to emailContent))
            when (decision) {
                is QualifierLlmGateway.QualifierDecision.Discard -> EmailDecision.DISCARD
                is QualifierLlmGateway.QualifierDecision.Delegate -> EmailDecision.DELEGATE
            }
        } catch (e: Exception) {
            logger.warn(e) { "Qualifier failed, defaulting to DELEGATE: ${e.message}" }
            EmailDecision.DELEGATE
        }
    }

    private fun formatEmailContent(email: ImapMessage): String =
        buildString {
            appendLine("FROM: ${email.from}")
            appendLine("TO: ${email.to}")
            appendLine("SUBJECT: ${email.subject}")
            appendLine("DATE: ${email.receivedAt}")
            appendLine()
            appendLine("CONTENT:")
            appendLine(email.content)
            if (email.attachments.isNotEmpty()) {
                appendLine()
                appendLine("ATTACHMENTS:")
                email.attachments.forEach { att ->
                    appendLine("  - ${att.fileName} (${att.contentType})")
                }
            }
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
                )

            val duration = System.currentTimeMillis() - startTime

            when (decision) {
                is QualifierLlmGateway.QualifierDecision.Discard -> {
                    logger.info { "Task ${task.id} (${task.taskType}) DISCARDED by qualifier in ${duration}ms - spam/noise" }

                    // Note: do not mutate context in non-NEW states; log for audit and delete (ephemeral)
                    logger.info { "QUALIFICATION_NOTES: task=${task.id} note=DISCARDED: Spam/noise detected" }

                    pendingTaskService.deleteTask(task.id)
                }

                is QualifierLlmGateway.QualifierDecision.Delegate -> {
                    logger.debug { "Task ${task.id} (${task.taskType}) DELEGATED in ${duration}ms to strong model" }

                    // Note: do not mutate context in non-NEW states; log for audit only
                    logger.info { "QUALIFICATION_NOTES: task=${task.id} note=DELEGATED: Actionable content" }

                    pendingTaskService.updateState(
                        task.id,
                        com.jervis.domain.task.PendingTaskState.QUALIFYING,
                        com.jervis.domain.task.PendingTaskState.DISPATCHED_GPU,
                    )
                }
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error(e) { "Qualification failed for task ${task.id} after ${duration}ms, delegating to strong model" }
            // Even if qualifier failed, delegate to strong model by transitioning state
            try {
                pendingTaskService.updateState(
                    task.id,
                    com.jervis.domain.task.PendingTaskState.QUALIFYING,
                    com.jervis.domain.task.PendingTaskState.DISPATCHED_GPU,
                )
            } catch (ie: Exception) {
                logger.warn(ie) { "Failed to transition task ${task.id} to DISPATCHED_GPU after qualifier error" }
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
