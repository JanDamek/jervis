package com.jervis.service.rag.pipeline

import com.jervis.service.rag.domain.DocumentChunk

/**
 * Strategy for deduplicating document chunks.
 */
fun interface ChunkDeduplicationStrategy {
    fun deduplicate(chunks: List<DocumentChunk>): List<DocumentChunk>
}
