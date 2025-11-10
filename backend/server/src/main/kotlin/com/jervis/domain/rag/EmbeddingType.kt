package com.jervis.domain.rag

/**
 * Types of embeddings used in the vector database
 */
enum class EmbeddingType {
    /** Text-based embeddings for documentation, comments, etc. */
    EMBEDDING_TEXT,

    /** Code-specific embeddings optimized for source code */
    EMBEDDING_CODE,
}
