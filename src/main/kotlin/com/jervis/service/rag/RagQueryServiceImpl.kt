package com.jervis.service.rag

import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagQueryResult
import com.jervis.service.gateway.LlmGateway
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Minimal Spring service implementation for RAG queries.
 * Provides a basic path that directly calls the internal chat model when no project context is provided.
 */
@Service
class RagQueryServiceImpl(
    private val llmGateway: LlmGateway,
) : RagQueryService {
    private val logger = KotlinLogging.logger {}

    override suspend fun processRagQuery(query: String): RagQueryResult {
        logger.debug { "RagQueryServiceImpl: processing query without project context" }
        val response = llmGateway.callLlm(
            type = ModelType.CHAT_INTERNAL,
            userPrompt = query,
        )
        return RagQueryResult(
            answer = response.answer,
            finishReason = response.finishReason,
            promptTokens = response.promptTokens,
            completionTokens = response.completionTokens,
            totalTokens = response.totalTokens,
        )
    }

    override suspend fun processRagQuery(query: String, projectId: ObjectId): RagQueryResult =
        TODO("RagQueryServiceImpl.processRagQuery with project context is not implemented yet")
}
