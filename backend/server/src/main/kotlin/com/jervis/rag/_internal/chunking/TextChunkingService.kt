package com.jervis.rag._internal.chunking

import com.jervis.rag.KnowledgeType
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Text chunking service - package-private.
 * Handles intelligent document splitting with overlap for context preservation.
 *
 * Best practices:
 * - Different strategies for CODE vs TEXT/DOCUMENT
 * - Semantic boundaries (paragraphs, sections, functions)
 * - Overlap for context continuity
 * - Metadata preservation
 */
@Component
internal class TextChunkingService {
    private val logger = KotlinLogging.logger {}

    companion object {
        // Chunk sizes optimized for embedding models
        private const val CODE_CHUNK_SIZE = 1500 // tokens ~= 6000 chars
        private const val TEXT_CHUNK_SIZE = 1000 // tokens ~= 4000 chars
        private const val OVERLAP_SIZE = 200 // ~800 chars overlap

        // Semantic boundaries
        private val PARAGRAPH_DELIMITERS = listOf("\n\n", "\r\n\r\n")
        private val CODE_DELIMITERS = listOf("\n\n", "}\n", ");\n", "}\n\n")
    }

    /**
     * Chunk document into fragments.
     * Returns list of chunks with metadata.
     */
    fun chunk(
        content: String,
        knowledgeType: KnowledgeType,
    ): List<ChunkResult> {
        logger.debug { "Chunking document: type=$knowledgeType, length=${content.length}" }

        if (content.length < 500) {
            // Small document - no chunking needed
            return listOf(
                ChunkResult(
                    content = content,
                    chunkIndex = 0,
                    totalChunks = 1,
                    startOffset = 0,
                    endOffset = content.length,
                ),
            )
        }

        val chunks =
            when (knowledgeType) {
                KnowledgeType.CODE -> chunkCode(content)
                else -> chunkText(content)
            }

        logger.info { "Chunked document into ${chunks.size} fragments (type=$knowledgeType)" }
        return chunks
    }

    /**
     * Chunk code with awareness of structure.
     */
    private fun chunkCode(content: String): List<ChunkResult> {
        val chunks = mutableListOf<ChunkResult>()
        var position = 0
        var chunkIndex = 0

        while (position < content.length) {
            val endPosition = findSemanticBoundary(content, position, CODE_CHUNK_SIZE, CODE_DELIMITERS)
            val chunkContent = content.substring(position, endPosition)

            chunks.add(
                ChunkResult(
                    content = chunkContent,
                    chunkIndex = chunkIndex,
                    totalChunks = -1, // Will be set later
                    startOffset = position,
                    endOffset = endPosition,
                ),
            )

            chunkIndex++
            position = maxOf(endPosition - OVERLAP_SIZE, endPosition)
        }

        // Set total chunks
        return chunks.mapIndexed { idx, chunk ->
            chunk.copy(chunkIndex = idx, totalChunks = chunks.size)
        }
    }

    /**
     * Chunk text with paragraph awareness.
     */
    private fun chunkText(content: String): List<ChunkResult> {
        val chunks = mutableListOf<ChunkResult>()
        var position = 0
        var chunkIndex = 0

        while (position < content.length) {
            val endPosition = findSemanticBoundary(content, position, TEXT_CHUNK_SIZE, PARAGRAPH_DELIMITERS)
            val chunkContent = content.substring(position, endPosition)

            chunks.add(
                ChunkResult(
                    content = chunkContent,
                    chunkIndex = chunkIndex,
                    totalChunks = -1,
                    startOffset = position,
                    endOffset = endPosition,
                ),
            )

            chunkIndex++
            position = maxOf(endPosition - OVERLAP_SIZE, endPosition)
        }

        return chunks.mapIndexed { idx, chunk ->
            chunk.copy(chunkIndex = idx, totalChunks = chunks.size)
        }
    }

    /**
     * Find semantic boundary for chunk end.
     * Tries to split at natural boundaries (paragraphs, function ends, etc.)
     */
    private fun findSemanticBoundary(
        content: String,
        start: Int,
        targetSize: Int,
        delimiters: List<String>,
    ): Int {
        val idealEnd = minOf(start + targetSize, content.length)

        // If at end, return
        if (idealEnd >= content.length) {
            return content.length
        }

        // Search for delimiter near ideal end
        val searchStart = maxOf(start, idealEnd - OVERLAP_SIZE)
        val searchEnd = minOf(content.length, idealEnd + OVERLAP_SIZE)

        for (delimiter in delimiters) {
            val delimiterIndex = content.indexOf(delimiter, searchStart)
            if (delimiterIndex in searchStart until searchEnd) {
                return delimiterIndex + delimiter.length
            }
        }

        // No semantic boundary found - hard split
        return idealEnd
    }
}

/**
 * Result of chunking operation.
 */
internal data class ChunkResult(
    val content: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val startOffset: Int,
    val endOffset: Int,
)
