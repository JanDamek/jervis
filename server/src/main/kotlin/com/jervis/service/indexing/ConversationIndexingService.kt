package com.jervis.service.indexing

import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.dto.ChatResponse
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.text.TextChunkingService
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Service for indexing completed conversations (Q&A pairs) into RAG for future retrieval.
 *
 * Stores both the user's question and agent's final answer as separate chunks in the vector database.
 * This allows the agent to learn from past conversations and provide better answers in the future.
 *
 * Only indexes foreground conversations (skips background tasks and quick mode).
 */
@Service
class ConversationIndexingService(
    private val textChunkingService: TextChunkingService,
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Indexes a completed conversation into RAG.
     *
     * @param plan The completed plan containing the original question
     * @param response The final response from the agent
     */
    suspend fun indexConversation(
        plan: Plan,
        response: ChatResponse,
    ) {
        // Skip background tasks and quick mode
        if (plan.backgroundMode) {
            logger.debug { "CONVERSATION_INDEXING: Skipping background task" }
            return
        }

        if (response.message.isBlank()) {
            logger.debug { "CONVERSATION_INDEXING: Skipping empty response" }
            return
        }

        try {
            logger.debug { "CONVERSATION_INDEXING: Indexing conversation for plan ${plan.id}" }

            // Create conversation text combining question and answer
            val conversationText =
                buildString {
                    appendLine("Q: ${plan.taskInstruction}")
                    appendLine()
                    appendLine("A: ${response.message}")
                }

            // Chunk the conversation if it's too long
            val chunks = textChunkingService.splitText(conversationText)

            logger.debug { "CONVERSATION_INDEXING: Split conversation into ${chunks.size} chunks" }

            // Store each chunk
            for ((index, chunk) in chunks.withIndex()) {
                try {
                    val embedding = embeddingGateway.callEmbedding(ModelTypeEnum.EMBEDDING_TEXT, chunk.text())

                    val ragDocument =
                        RagDocument(
                            projectId = plan.projectId,
                            clientId = plan.clientId,
                            ragSourceType = RagSourceType.AGENT,
                            summary = chunk.text(),
                            // Universal metadata
                            from = "agent",
                            subject = plan.taskInstruction.take(100),
                            timestamp =
                                java.time.Instant
                                    .now()
                                    .toString(),
                            parentRef = plan.id.toHexString(),
                            indexInParent = index,
                            totalSiblings = chunks.size,
                            contentType = "conversation",
                            // Conversation-specific
                            language = plan.originalLanguage,
                            symbolName = "conversation-${plan.id}",
                            chunkId = index,
                            chunkOf = chunks.size,
                        )

                    vectorStorage.store(ModelTypeEnum.EMBEDDING_TEXT, ragDocument, embedding)
                } catch (e: Exception) {
                    logger.error(e) { "CONVERSATION_INDEXING: Failed to store chunk $index" }
                }
            }

            logger.info { "CONVERSATION_INDEXING: Successfully indexed conversation ${plan.id} (${chunks.size} chunks)" }
        } catch (e: Exception) {
            logger.error(e) { "CONVERSATION_INDEXING: Failed to index conversation ${plan.id}" }
        }
    }
}
