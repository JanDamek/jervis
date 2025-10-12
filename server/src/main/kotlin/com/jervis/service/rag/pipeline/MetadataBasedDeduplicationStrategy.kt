package com.jervis.service.rag.pipeline

import com.jervis.service.rag.domain.DocumentChunk
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Deduplication strategy based on document metadata (projectId, path, lineRange).
 * Removes duplicate chunks that point to the same source location.
 */
@Component
class MetadataBasedDeduplicationStrategy : ChunkDeduplicationStrategy {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun deduplicate(chunks: List<DocumentChunk>): List<DocumentChunk> {
        val seen = mutableSetOf<String>()
        val deduplicated =
            chunks.filter { chunk ->
                val key = buildKey(chunk)
                seen.add(key)
            }

        logger.debug { "DEDUPLICATION: ${chunks.size} -> ${deduplicated.size} chunks" }
        return deduplicated
    }

    private fun buildKey(chunk: DocumentChunk): String =
        listOf(
            chunk.metadata["projectId"],
            chunk.metadata["path"],
            chunk.metadata["lineRange"],
        ).joinToString("-")
}
