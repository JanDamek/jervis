package com.jervis.service.chat

import com.jervis.common.types.TaskId
import com.jervis.configuration.ChatHistoryMessageDto
import com.jervis.configuration.ChatHistoryPayloadDto
import com.jervis.configuration.ChatSummaryBlockDto
import com.jervis.configuration.CompressChatRequestDto
import com.jervis.configuration.PythonOrchestratorClient
import com.jervis.entity.ChatSummaryDocument
import com.jervis.repository.ChatMessageRepository
import com.jervis.repository.ChatSummaryRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.format.DateTimeFormatter

/**
 * ChatHistoryService — prepares chat history payload for orchestrator
 * and handles async compression of old messages into summary blocks.
 *
 * Two main operations:
 * 1. prepareChatHistoryPayload() — called before dispatching to orchestrator
 *    Loads recent messages (verbatim) + existing summaries (compressed)
 * 2. compressIfNeeded() — called after orchestration completes
 *    If >20 unsummarized messages exist, compresses them via Python LLM
 */
@Service
class ChatHistoryService(
    private val chatMessageRepository: ChatMessageRepository,
    private val chatSummaryRepository: ChatSummaryRepository,
    private val pythonOrchestratorClient: PythonOrchestratorClient,
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val RECENT_MESSAGE_COUNT = 20
        const val MAX_SUMMARY_BLOCKS = 15

        /**
         * Check if message content is an error message that should be filtered out.
         *
         * Error messages have these patterns:
         * - JSON with "error" key: {"error": {"type": "...", "message": "..."}}
         * - Plain text starting with "Error:" or "Chyba:"
         * - Contains "llm_call_failed" or "Operation not allowed"
         */
        private fun isErrorMessage(content: String): Boolean {
            val contentLower = content.trim().lowercase()

            // JSON error object
            if (contentLower.startsWith("{") && "\"error\"" in contentLower) {
                return true
            }

            // Plain text errors (Czech + English)
            if (contentLower.startsWith("error:") || contentLower.startsWith("chyba:")) {
                return true
            }

            // Specific error signatures
            if ("llm_call_failed" in contentLower) {
                return true
            }
            if ("operation not allowed" in contentLower) {
                return true
            }

            return false
        }
    }

    /**
     * Prepare chat history payload for orchestrator dispatch.
     *
     * Returns:
     * - Last 20 messages verbatim (recent context)
     * - Up to 15 summary blocks (compressed older history)
     * - Total message count (for context)
     *
     * Returns null if no messages exist (no history to send).
     */
    suspend fun prepareChatHistoryPayload(taskId: TaskId): ChatHistoryPayloadDto? {
        val allMessages = chatMessageRepository.findByTaskIdOrderBySequenceAsc(taskId).toList()

        if (allMessages.isEmpty()) return null

        val totalCount = allMessages.size.toLong()

        // Recent messages: last 20 — FILTER OUT ERROR MESSAGES
        val recentMessages = allMessages
            .takeLast(RECENT_MESSAGE_COUNT)
            .filterNot { isErrorMessage(it.content) }  // Filter out error messages
            .map { msg ->
                ChatHistoryMessageDto(
                    role = msg.role.name.lowercase(),
                    content = msg.content,
                    timestamp = DateTimeFormatter.ISO_INSTANT.format(msg.timestamp),
                    sequence = msg.sequence,
                )
            }

        if (recentMessages.isEmpty() && allMessages.isNotEmpty()) {
            logger.warn {
                "CHAT_HISTORY_ALL_ERRORS | taskId=$taskId | All recent messages were errors, filtered out"
            }
        }

        // Summary blocks: existing compressed summaries
        val summaryBlocks = chatSummaryRepository
            .findByTaskIdOrderBySequenceEndAsc(taskId)
            .toList()
            .takeLast(MAX_SUMMARY_BLOCKS)
            .map { summary ->
                ChatSummaryBlockDto(
                    sequenceRange = "${summary.sequenceStart}-${summary.sequenceEnd}",
                    summary = summary.summary,
                    keyDecisions = summary.keyDecisions,
                    topics = summary.topics,
                    isCheckpoint = summary.isCheckpoint,
                    checkpointReason = summary.checkpointReason,
                )
            }

        logger.info {
            "CHAT_HISTORY_PREPARED | taskId=$taskId | recentMessages=${recentMessages.size} " +
                "| summaryBlocks=${summaryBlocks.size} | totalMessages=$totalCount"
        }

        return ChatHistoryPayloadDto(
            recentMessages = recentMessages,
            summaryBlocks = summaryBlocks,
            totalMessageCount = totalCount,
        )
    }

    /**
     * Compress old messages into summary blocks if needed.
     * Called asynchronously after orchestration completes.
     *
     * Algorithm:
     * 1. Find the last summarized sequence number
     * 2. Count unsummarized messages before the recent window
     * 3. If > RECENT_MESSAGE_COUNT unsummarized messages → compress via Python LLM
     * 4. Store ChatSummaryDocument in MongoDB
     */
    suspend fun compressIfNeeded(taskId: TaskId, clientId: String) {
        try {
            val totalMessages = chatMessageRepository.countByTaskId(taskId)
            if (totalMessages <= RECENT_MESSAGE_COUNT) {
                logger.debug { "COMPRESS_SKIP | taskId=$taskId | totalMessages=$totalMessages (≤$RECENT_MESSAGE_COUNT)" }
                return
            }

            // Find where we left off
            val lastSummary = chatSummaryRepository.findFirstByTaskIdOrderBySequenceEndDesc(taskId)
            val lastSummarizedSequence = lastSummary?.sequenceEnd ?: 0L

            // Load all messages
            val allMessages = chatMessageRepository.findByTaskIdOrderBySequenceAsc(taskId).toList()

            // Messages that need summarizing: after last summary, before recent window
            val recentStart = allMessages.size - RECENT_MESSAGE_COUNT
            if (recentStart <= 0) return

            val unsummarized = allMessages
                .take(recentStart)
                .filter { it.sequence > lastSummarizedSequence }

            if (unsummarized.size < RECENT_MESSAGE_COUNT) {
                logger.debug { "COMPRESS_SKIP | taskId=$taskId | unsummarized=${unsummarized.size} (<$RECENT_MESSAGE_COUNT)" }
                return
            }

            logger.info { "COMPRESS_START | taskId=$taskId | unsummarized=${unsummarized.size}" }

            // Convert to DTOs for Python
            val messageDtos = unsummarized.map { msg ->
                ChatHistoryMessageDto(
                    role = msg.role.name.lowercase(),
                    content = msg.content,
                    timestamp = DateTimeFormatter.ISO_INSTANT.format(msg.timestamp),
                    sequence = msg.sequence,
                )
            }

            // Call Python LLM to compress
            val response = pythonOrchestratorClient.compressChat(
                CompressChatRequestDto(
                    messages = messageDtos,
                    previousSummary = lastSummary?.summary,
                    clientId = clientId,
                    taskId = taskId.toString(),
                ),
            )

            // Store summary
            val summaryDoc = ChatSummaryDocument(
                taskId = taskId,
                sequenceStart = unsummarized.first().sequence,
                sequenceEnd = unsummarized.last().sequence,
                summary = response.summary,
                keyDecisions = response.keyDecisions,
                topics = response.topics,
                isCheckpoint = response.isCheckpoint,
                checkpointReason = response.checkpointReason,
                messageCount = unsummarized.size,
            )
            chatSummaryRepository.save(summaryDoc)

            logger.info {
                "COMPRESS_DONE | taskId=$taskId | range=${summaryDoc.sequenceStart}-${summaryDoc.sequenceEnd} " +
                    "| messageCount=${unsummarized.size} | summaryLength=${response.summary.length}"
            }
        } catch (e: Exception) {
            logger.warn(e) { "COMPRESS_FAILED | taskId=$taskId | ${e.message}" }
        }
    }
}
