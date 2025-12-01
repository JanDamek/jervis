package com.jervis.rag._internal.chunking

import com.jervis.configuration.properties.TextChunkingProperties
import com.jervis.rag.KnowledgeType
import dev.langchain4j.data.document.Document
import dev.langchain4j.data.document.DocumentSplitter
import dev.langchain4j.data.document.splitter.DocumentSplitters
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Text chunking service using langchain4j DocumentSplitters.
 * Handles intelligent document splitting with overlap for context preservation.
 *
 * Uses recursive document splitter from langchain4j which:
 * - Respects semantic boundaries (paragraphs, sections, sentences)
 * - Handles overlap for context continuity
 * - Properly tokenizes content
 */
@Component
internal class TextChunkingService(
    private val properties: TextChunkingProperties,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Chunk document into fragments using langchain4j recursive splitter.
     * Returns list of chunks with metadata.
     */
    fun chunk(
        content: String,
        knowledgeType: KnowledgeType,
    ): List<ChunkResult> {
        logger.debug { "Chunking document: type=$knowledgeType, length=${content.length}" }

        if (content.isBlank()) {
            return emptyList()
        }

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

        // Choose token size based on content type
        val maxTokens =
            when (knowledgeType) {
                KnowledgeType.CODE -> properties.maxTokens * 2
                else -> properties.maxTokens
            }
        val overlapTokens = (maxTokens * properties.overlapPercentage) / 100

        // Use langchain4j recursive splitter
        val splitter: DocumentSplitter = DocumentSplitters.recursive(maxTokens, overlapTokens)
        val document = Document.from(content)
        val segments = splitter.split(document)

        // Convert to ChunkResult with metadata
        val chunks =
            segments.mapIndexed { index, segment ->
                val text = segment.text()
                val startOffset = content.indexOf(text).takeIf { it >= 0 } ?: 0
                ChunkResult(
                    content = text,
                    chunkIndex = index,
                    totalChunks = segments.size,
                    startOffset = startOffset,
                    endOffset = startOffset + text.length,
                )
            }

        logger.info { "Chunked document into ${chunks.size} fragments using langchain4j (type=$knowledgeType)" }
        return chunks
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
