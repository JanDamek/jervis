package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.domain.plan.Plan
import com.jervis.rag.DocumentToStore
import com.jervis.rag.EmbeddingType
import com.jervis.rag.KnowledgeService
import com.jervis.rag.KnowledgeType
import com.jervis.rag.StoreRequest
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

/**
 * Sequential indexing tool with chunking for large documents.
 *
 * NEW ARCHITECTURE (Graph-Based Routing):
 * - Used by KoogQualifierAgent (CPU) to index DATA_PROCESSING tasks
 * - Chunks large documents with overlap (4000 char blocks, 200 char overlap)
 * - Creates RAG chunks for semantic search
 * - Supports single-pass (small) and multi-pass (large) processing
 *
 * Chunking Strategy:
 * - Check total content size
 * - If < 4000 chars: single-pass indexing
 * - If >= 4000 chars: multi-pass with overlap
 * - Overlap ensures context continuity across chunk boundaries
 */
@LLMDescription("Index content into RAG with automatic chunking for large documents")
class SequentialIndexingTool(
    private val plan: Plan,
    private val knowledgeService: KnowledgeService,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
        private const val CHUNK_SIZE = 4000
        private const val OVERLAP_SIZE = 200
    }

    @Tool
    @LLMDescription("""Index document content into RAG with automatic chunking.
Handles both small (single-pass) and large (multi-pass with overlap) documents.
Returns indexing statistics including total chunks created.

For large documents:
- Splits into 4000 character chunks
- 200 character overlap between chunks for context continuity
- Each chunk indexed separately with sequence metadata

Use this for indexing emails, Jira issues, Git commits, and other documents.""")
    fun indexDocument(
        @LLMDescription("Unique document identifier (e.g., 'email:abc123', 'jira:PROJ-123')")
        documentId: String,

        @LLMDescription("Full document content to index")
        content: String,

        @LLMDescription("Document title/subject")
        title: String,

        @LLMDescription("Document location/source (e.g., 'Email (INBOX)', 'Jira Issue')")
        location: String,

        @LLMDescription("Knowledge type: DOCUMENT, CODE, MEMORY, etc.")
        knowledgeType: String = "DOCUMENT",

        @LLMDescription("Related document IDs for cross-referencing")
        relatedDocs: List<String> = emptyList(),
    ): String = runBlocking {
        val trimmedContent = content.trim()
        if (trimmedContent.isBlank()) {
            throw IllegalArgumentException("Content cannot be blank")
        }

        val type = try {
            KnowledgeType.valueOf(knowledgeType)
        } catch (e: Exception) {
            logger.warn { "Invalid knowledge type '$knowledgeType', using DOCUMENT" }
            KnowledgeType.DOCUMENT
        }

        logger.info { "SEQUENTIAL_INDEX: documentId='$documentId', size=${trimmedContent.length}, type=$type" }

        // Determine if chunking is needed
        if (trimmedContent.length <= CHUNK_SIZE) {
            // Single-pass indexing for small documents
            return@runBlocking indexSinglePass(documentId, trimmedContent, title, location, type, relatedDocs)
        } else {
            // Multi-pass indexing with overlap for large documents
            return@runBlocking indexMultiPass(documentId, trimmedContent, title, location, type, relatedDocs)
        }
    }

    /**
     * Single-pass indexing for small documents (< 4000 chars).
     */
    private suspend fun indexSinglePass(
        documentId: String,
        content: String,
        title: String,
        location: String,
        type: KnowledgeType,
        relatedDocs: List<String>,
    ): String {
        logger.info { "Single-pass indexing for $documentId (${content.length} chars)" }

        val document = DocumentToStore(
            documentId = documentId,
            content = content,
            clientId = plan.clientDocument.id,
            projectId = plan.projectDocument?.id,
            type = type,
            embeddingType = EmbeddingType.TEXT,
            title = title,
            location = location,
            relatedDocs = relatedDocs,
        )

        val result = knowledgeService.store(StoreRequest(listOf(document)))
        val stored = result.documents.firstOrNull()
            ?: throw IllegalStateException("Store operation returned no documents")

        return buildString {
            appendLine("✓ Indexed document (single-pass)")
            appendLine("  Document ID: $documentId")
            appendLine("  Title: $title")
            appendLine("  Location: $location")
            appendLine("  Content Size: ${content.length} chars")
            appendLine("  Total Chunks: ${stored.totalChunks}")
            appendLine("  Indexing Method: Single-pass (small document)")
        }
    }

    /**
     * Multi-pass indexing with overlap for large documents (>= 4000 chars).
     */
    private suspend fun indexMultiPass(
        documentId: String,
        content: String,
        title: String,
        location: String,
        type: KnowledgeType,
        relatedDocs: List<String>,
    ): String {
        logger.info { "Multi-pass indexing for $documentId (${content.length} chars)" }

        val chunks = createChunksWithOverlap(content)
        logger.info { "Created ${chunks.size} chunks with overlap for $documentId" }

        val documentsToStore = chunks.mapIndexed { index, chunk ->
            DocumentToStore(
                documentId = "$documentId:chunk:$index",
                content = chunk,
                clientId = plan.clientDocument.id,
                projectId = plan.projectDocument?.id,
                type = type,
                embeddingType = EmbeddingType.TEXT,
                title = "$title (Part ${index + 1}/${chunks.size})",
                location = location,
                relatedDocs = relatedDocs + listOf(documentId), // Link back to parent
            )
        }

        val result = knowledgeService.store(StoreRequest(documentsToStore))
        val totalChunks = result.documents.sumOf { it.totalChunks }

        return buildString {
            appendLine("✓ Indexed document (multi-pass with overlap)")
            appendLine("  Document ID: $documentId")
            appendLine("  Title: $title")
            appendLine("  Location: $location")
            appendLine("  Content Size: ${content.length} chars")
            appendLine("  Logical Chunks: ${chunks.size}")
            appendLine("  Total RAG Chunks: $totalChunks")
            appendLine("  Chunk Size: $CHUNK_SIZE chars")
            appendLine("  Overlap: $OVERLAP_SIZE chars")
            appendLine("  Indexing Method: Multi-pass (large document)")
        }
    }

    /**
     * Create chunks with overlap for continuity.
     *
     * Strategy:
     * - 4000 char chunks with 200 char overlap
     * - Last chunk may be smaller (no padding)
     * - Overlap ensures context spans chunk boundaries
     */
    private fun createChunksWithOverlap(content: String): List<String> {
        val chunks = mutableListOf<String>()
        var startIndex = 0

        while (startIndex < content.length) {
            val endIndex = minOf(startIndex + CHUNK_SIZE, content.length)
            val chunk = content.substring(startIndex, endIndex)
            chunks.add(chunk)

            // Move forward by (CHUNK_SIZE - OVERLAP_SIZE) to create overlap
            startIndex += (CHUNK_SIZE - OVERLAP_SIZE)

            // If remaining content is smaller than overlap, include it in last chunk
            if (content.length - startIndex < OVERLAP_SIZE) {
                break
            }
        }

        return chunks
    }
}
