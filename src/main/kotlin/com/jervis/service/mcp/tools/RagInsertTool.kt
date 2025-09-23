package com.jervis.service.mcp.tools

import com.jervis.common.Constants.GLOBAL_ID
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * RAG Insert tool for storing content into the vector database.
 * Supports both client/project-scoped and global insertion modes.
 */
@Service
class RagInsertTool(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.RAG_INSERT

    @Serializable
    data class RagInsertRequest(
        val content: String = "",
        val documentType: String = "TEXT", // Maps to RagDocumentType
        val sourceType: String = "AGENT", // Maps to RagSourceType
        val embedding: String = "text", // "text" or "code"
        val isGlobal: Boolean = false, // If true, skips client/project context
        // Optional metadata
        val source: String? = null,
        val language: String? = null,
        val module: String? = null,
        val path: String? = null,
        val packageName: String? = null,
        val className: String? = null,
        val methodName: String? = null,
        val inspirationOnly: Boolean = false,
    )

    @Serializable
    data class RagInsertParams(
        val insertions: List<RagInsertRequest> = listOf(RagInsertRequest()),
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String = "",
    ): RagInsertParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.RAG_INSERT,
                userPrompt = taskDescription,
                quick = context.quick,
                responseSchema = RagInsertParams(),
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
        logger.debug {
            "RAG_INSERT_START: Executing RAG insert for taskDescription='$taskDescription', contextId='${context.id}', planId='${plan.id}'"
        }

        val insertParams = parseTaskDescription(taskDescription, context, stepContext)

        logger.debug { "RAG_INSERT_PARSED: insertParams=$insertParams" }

        if (insertParams.insertions.isEmpty()) {
            logger.warn { "RAG_INSERT_NO_INSERTIONS: No insertion parameters provided for taskDescription='$taskDescription'" }
            return ToolResult.error("No insertion parameters provided")
        }

        // Execute all insertions in parallel
        return coroutineScope {
            val insertResults =
                insertParams.insertions
                    .mapIndexed { index, insertRequest ->
                        async {
                            executeRagInsert(context, insertRequest, index + 1)
                        }
                    }.awaitAll()

            val successfulInserts = insertResults.filterIsInstance<ToolResult.Ok>()
            val failedInserts = insertResults.filterIsInstance<ToolResult.Error>()

            if (failedInserts.isNotEmpty()) {
                val errorMessages =
                    failedInserts.mapIndexed { index, error ->
                        "Insert ${index + 1}: ${error.errorMessage ?: error.output}"
                    }
                logger.error { "RAG_INSERT_ERRORS: Some insertions failed: ${errorMessages.joinToString("; ")}" }
                return@coroutineScope ToolResult.error("Some insertions failed:\n${errorMessages.joinToString("\n")}")
            }

            val results = successfulInserts.map { it.output }.joinToString("\n")
            logger.info { "RAG_INSERT_SUCCESS: Successfully inserted ${successfulInserts.size} documents" }
            ToolResult.ok("Successfully inserted ${successfulInserts.size} documents:\n$results")
        }
    }

    private suspend fun executeRagInsert(
        context: TaskContext,
        insertRequest: RagInsertRequest,
        insertIndex: Int,
    ): ToolResult {
        logger.debug {
            "RAG_INSERT_EXECUTE: Starting insert $insertIndex: content length=${insertRequest.content.length}, global=${insertRequest.isGlobal}"
        }

        if (insertRequest.content.isBlank()) {
            logger.warn { "RAG_INSERT_EMPTY_CONTENT: Empty content for insert $insertIndex" }
            return ToolResult.error("Empty content provided for insert $insertIndex")
        }

        val modelType =
            when (insertRequest.embedding.lowercase()) {
                "text" -> ModelType.EMBEDDING_TEXT
                "code" -> ModelType.EMBEDDING_CODE
                else -> {
                    logger.error { "RAG_INSERT_UNSUPPORTED_EMBEDDING: ${insertRequest.embedding}" }
                    return ToolResult.error("Unsupported embedding type: ${insertRequest.embedding}")
                }
            }

        logger.debug { "RAG_INSERT_MODEL_TYPE: Using modelType=$modelType for insert $insertIndex" }

        val embedding =
            try {
                embeddingGateway.callEmbedding(modelType, insertRequest.content)
            } catch (e: Exception) {
                logger.error(e) { "RAG_INSERT_EMBEDDING_ERROR: Failed to generate embedding for insert $insertIndex" }
                return ToolResult.error("Embedding failed for insert $insertIndex: ${e.message}")
            }

        if (embedding.isEmpty()) {
            logger.warn { "RAG_INSERT_NO_EMBEDDING: No embedding produced for insert $insertIndex" }
            return ToolResult.error("No embedding produced for insert $insertIndex")
        }

        logger.debug { "RAG_INSERT_EMBEDDING_SUCCESS: Generated embedding with ${embedding.size} dimensions for insert $insertIndex" }

        // Parse document type and source type
        val documentType =
            try {
                RagDocumentType.valueOf(insertRequest.documentType.uppercase())
            } catch (e: IllegalArgumentException) {
                logger.warn { "RAG_INSERT_INVALID_DOC_TYPE: Invalid documentType '${insertRequest.documentType}', using TEXT" }
                RagDocumentType.TEXT
            }

        val sourceType =
            try {
                RagSourceType.valueOf(insertRequest.sourceType.uppercase())
            } catch (e: IllegalArgumentException) {
                logger.warn { "RAG_INSERT_INVALID_SOURCE_TYPE: Invalid sourceType '${insertRequest.sourceType}', using AGENT" }
                RagSourceType.AGENT
            }

        // Create RAG document with proper context handling
        val ragDocument =
            RagDocument(
                projectId = if (insertRequest.isGlobal) GLOBAL_ID else context.projectDocument.id,
                documentType = documentType,
                ragSourceType = sourceType,
                pageContent = insertRequest.content,
                clientId = if (insertRequest.isGlobal) GLOBAL_ID else context.clientDocument.id,
                source = insertRequest.source,
                language = insertRequest.language,
                module = insertRequest.module,
                path = insertRequest.path,
                packageName = insertRequest.packageName,
                className = insertRequest.className,
                methodName = insertRequest.methodName,
                timestamp = System.currentTimeMillis(),
                isDefaultBranch = true,
                inspirationOnly = insertRequest.inspirationOnly,
                createdAt = Instant.now(),
                lastModified = Instant.now(),
            )

        // Store the document
        val pointId =
            try {
                logger.debug { "RAG_INSERT_STORE_START: Storing document for insert $insertIndex" }
                vectorStorage.store(modelType, ragDocument, embedding)
            } catch (e: Exception) {
                logger.error(e) { "RAG_INSERT_STORE_ERROR: Failed to store document for insert $insertIndex" }
                return ToolResult.error("Failed to store document for insert $insertIndex: ${e.message}")
            }

        val contextInfo =
            if (insertRequest.isGlobal) "global" else "client=${context.clientDocument.name}, project=${context.projectDocument.name}"
        val result =
            "Insert $insertIndex: Successfully stored document with ID $pointId (${insertRequest.content.length} chars, $contextInfo, type=${documentType.name}, source=${sourceType.name})"

        logger.info { "RAG_INSERT_SUCCESS: $result" }
        return ToolResult.ok(result)
    }
}
