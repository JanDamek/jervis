package com.jervis.persistence.mongo

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * MongoDB document representing metadata for a RAG chunk.
 * This document stores detailed information about chunks that are indexed in the vector database.
 */
@Document(collection = "rag_chunk_metadata")
data class RagChunkMetadataDocument(
    /**
     * Unique identifier for the chunk, matches the ID in the vector database.
     */
    @Id
    val chunkId: String,
    
    /**
     * ID of the project this chunk belongs to.
     */
    val projectId: String,
    
    /**
     * Path to the file from which this chunk was extracted.
     */
    val filePath: String,
    
    /**
     * Position information in the file.
     */
    val positionInFile: Int,
    
    /**
     * Brief summary or description of the chunk content.
     */
    val contentSummary: String,
    
    /**
     * The full content of the chunk.
     */
    val fullContent: String,
    
    /**
     * ID of the embedding in the vector database.
     */
    val embeddingId: String,
    
    /**
     * Timestamp when the chunk was created.
     */
    val createdAt: Instant = Instant.now(),
    
    /**
     * Timestamp when the chunk was last updated.
     */
    val updatedAt: Instant = Instant.now(),
    
    /**
     * Status of the chunk (e.g., active, obsolete).
     */
    val status: String = "active",
    
    /**
     * Type of the document (e.g., code, text).
     */
    val documentType: String,
    
    /**
     * Programming language if applicable.
     */
    val language: String? = null,
    
    /**
     * Additional metadata as key-value pairs.
     */
    val metadata: Map<String, Any> = emptyMap()
)