package com.jervis.rag._internal.model

/**
 * Metadata stored with RAG chunks in Weaviate.
 */
data class RagMetadata(
    val projectId: String,
    val sourcePath: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val entityTypes: List<String>,
    val contentHash: String,
    val graphRefs: List<String>,
)
