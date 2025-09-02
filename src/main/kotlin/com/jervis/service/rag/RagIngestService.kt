package com.jervis.service.rag

import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.mcp.domain.ToolResult
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Minimal ingestion service that stores agent step artifacts into the vector store.
 * Skips ingestion when projectId is missing.
 */
@Service
class RagIngestService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun ingestStep(
        context: TaskContext,
        plan: Plan,
        toolName: String,
        taskDescription: String,
        output: ToolResult,
    ): String? {
        val projectId = context.projectDocument.id
        val content =
            buildString {
                appendLine("Agent step artifact")
                appendLine("contextId=${context.id}")
                appendLine("clientId=${context.clientDocument.id}")
                appendLine("projectId=${context.projectDocument.id}")
                appendLine("tool=$toolName")
                appendLine("taskDescription=$taskDescription")
                appendLine("output=${output.render().take(4000)}")
            }
        val embedding =
            try {
                embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, content)
            } catch (t: Throwable) {
                logger.warn(t) { "RAG_EMBED_FAIL: tool=$toolName contextId=${context.id}" }
                emptyList()
            }
        if (embedding.isEmpty()) {
            logger.debug { "RAG_INGEST_SKIP: Empty embedding tool=$toolName contextId=${context.id}" }
            return null
        }
        val doc =
            RagDocument(
                projectId = projectId,
                documentType = RagDocumentType.ACTION,
                ragSourceType = RagSourceType.AGENT,
                pageContent = content,
                clientId = context.clientDocument.id,
                language = (plan.originalLanguage.takeIf { it.isNotBlank() } ?: "en"),
                module = toolName,
                path = "agent/step/$toolName",
            )
        return try {
            val pointId = vectorStorage.store(ModelType.EMBEDDING_TEXT, doc, embedding)
            logger.info { "RAG_INGESTED: tool=$toolName contextId=${context.id} pointId=$pointId" }
            pointId
        } catch (t: Throwable) {
            logger.warn(t) { "RAG_STORE_FAIL: tool=$toolName contextId=${context.id}" }
            null
        }
    }
}
