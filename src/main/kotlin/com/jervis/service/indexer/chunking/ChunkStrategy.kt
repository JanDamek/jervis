package com.jervis.service.indexer.chunking

/**
 * Interface for chunking strategies.
 * Different implementations can provide specialized chunking for different types of content.
 */
interface ChunkStrategy {
    /**
     * Split content into chunks
     *
     * @param content The content to split
     * @param metadata Additional metadata about the content (e.g., language, format)
     * @param maxChunkSize The maximum size of each chunk in tokens
     * @param overlapSize The number of tokens to overlap between chunks
     * @return A list of chunks
     */
    fun splitContent(
        content: String,
        metadata: Map<String, String> = emptyMap(),
        maxChunkSize: Int = 1024,
        overlapSize: Int = 200,
    ): List<Chunk>

    /**
     * Check if this strategy can handle the given content type
     *
     * @param contentType The type of content (e.g., language, format)
     * @return True if this strategy can handle the content type
     */
    fun canHandle(contentType: String): Boolean
}

/**
 * Data class representing a chunk of content
 */
data class Chunk(
    val content: String,
    val metadata: Map<String, Any> = emptyMap(),
)
