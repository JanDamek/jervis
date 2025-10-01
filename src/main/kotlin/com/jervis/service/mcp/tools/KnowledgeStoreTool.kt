package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Knowledge Store tool for storing content into the vector database.
 * Supports both client/project-scoped and global insertion modes.
 */
@Service
class KnowledgeStoreTool(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.KNOWLEDGE_STORE

    @Serializable
    data class KnowledgeStoreParams(
        val content: String = "",
        val embedding: String = "text",
        val global: Boolean? = null,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String = "",
    ): KnowledgeStoreParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.KNOWLEDGE_STORE,
                mappingValue = mapOf("taskDescription" to taskDescription),
                quick = context.quick,
                responseSchema = KnowledgeStoreParams(),
                stepContext = stepContext,
            )
        return llmResponse
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        val parsed = parseTaskDescription(taskDescription, context, stepContext)

        return executeKnowledgeStoreOperation(parsed, context)
    }

    private suspend fun executeKnowledgeStoreOperation(
        params: KnowledgeStoreParams,
        context: TaskContext,
    ): ToolResult {
        logger.debug {
            "KNOWLEDGE_STORE_START: Storing content with length=${params.content.length}, embedding=${params.embedding}"
        }

        val modelType =
            when (params.embedding.lowercase()) {
                "text" -> ModelType.EMBEDDING_TEXT
                "code" -> ModelType.EMBEDDING_CODE
                else -> {
                    logger.error { "KNOWLEDGE_STORE_UNSUPPORTED_EMBEDDING: ${params.embedding}" }
                    return ToolResult.error("Unsupported embedding type: ${params.embedding}")
                }
            }

        logger.debug { "KNOWLEDGE_STORE_MODEL_TYPE: Using modelType=$modelType" }

        val embedding = embeddingGateway.callEmbedding(modelType, params.content)

        logger.debug { "KNOWLEDGE_STORE_EMBEDDING_SUCCESS: Generated embedding with ${embedding.size} dimensions" }

        val sourceType = RagSourceType.AGENT

        // Create RAG document with structured metadata
        // Note: Global storage is handled at the vector storage level
        val ragDocument =
            RagDocument(
                projectId = context.projectDocument.id,
                ragSourceType = sourceType,
                summary = params.content,
                clientId = context.clientDocument.id,
                language = null,
                path = null,
                packageName = null,
                className = null,
                methodName = null,
                createdAt = Instant.now(),
            )

        // Store the document
        logger.debug { "KNOWLEDGE_STORE_STORE_START: Storing document (global=${params.global})" }
        val pointId = vectorStorage.store(modelType, ragDocument, embedding)

        val contextInfo = "client=${context.clientDocument.name}, project=${context.projectDocument.name}"
        val result =
            "Successfully stored document with ID $pointId (${params.content.length} chars, $contextInfo, source=${sourceType.name})"

        logger.info { "KNOWLEDGE_STORE_SUCCESS: $result" }
        return ToolResult.ok(result)
    }
}
