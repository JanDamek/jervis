package com.jervis.service.rag.pipeline

import com.jervis.domain.plan.Plan
import com.jervis.service.rag.RagService
import com.jervis.service.rag.domain.DocumentChunk
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Content synthesis strategy that uses LLM to generate a final answer.
 * Combines all filtered chunks from multiple queries into a comprehensive response.
 */
@Component
class LlmContentSynthesisStrategy {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun synthesize(
        queryResults: List<RagService.QueryResult>,
        originalQuery: String,
        plan: Plan,
    ): String {
        logger.info { "SYNTHESIS: Synthesizing ${queryResults.size} query results" }

        val allFilteredChunks = queryResults.flatMap { it.filteredChunks }
        if (allFilteredChunks.isEmpty()) {
            logger.info { "SYNTHESIS: No relevant information found across all queries" }
            return "No relevant information found in the knowledge base for this query."
        }

        logger.info { "SYNTHESIS: Found ${allFilteredChunks.size} relevant chunks, formatting for LLM" }
        return formatChunksForLlm(allFilteredChunks)
    }

    private fun formatChunksForLlm(chunks: List<DocumentChunk>): String =
        buildString {
            append("KB_RESULTS (${chunks.size} chunks, relevance-sorted):\n")

            chunks.sortedByDescending { it.score }.forEachIndexed { index, chunk ->
                append("\n[${index + 1}]")

                val sources = extractRelevantMetadata(chunk.metadata)
                if (sources.isNotEmpty()) {
                    val sourceStr = sources.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    append(" src={$sourceStr}")
                }
                append("\n")
                append(chunk.content.trim())
                append("\n")
            }
        }

    private fun extractRelevantMetadata(metadata: Map<String, String>): Map<String, String> =
        buildMap {
            // Type discrimination with specific formatting
            metadata["ragSourceType"]?.let { type ->
                when (type) {
                    "EMAIL" -> put("type", "email")
                    "EMAIL_ATTACHMENT" -> {
                        val fileName = metadata["fileName"] ?: "unknown"
                        val index = metadata["indexInParent"] ?: "?"
                        put("type", "attachment[$index]:$fileName")
                    }

                    "GIT_HISTORY" -> put("type", "commit")
                    "MEETING_TRANSCRIPT" -> put("type", "meeting")
                    "AUDIO_TRANSCRIPT" -> put("type", "audio")
                    "AGENT" -> put("type", "conversation")
                    "DOCUMENTATION" -> put("type", "doc")
                    "JOERN", "CODE_FALLBACK" -> put("type", "code")
                    else -> put("type", type.lowercase().replace("_", "-"))
                }
            }

            // Universal context metadata
            metadata["from"]?.let { put("from", it) }
            metadata["subject"]?.let { put("subj", it.take(50)) }
            metadata["timestamp"]?.let { put("when", it.substringBefore('T')) }

            // Relationship metadata
            metadata["totalSiblings"]?.takeIf { it != "0" }?.let { put("related", it) }
            metadata["indexInParent"]?.let { put("index", it) }
            metadata["parentRef"]?.let { put("parent", it.take(12)) }

            // Source references
            metadata["sourceUri"]?.let { put("uri", it) }
            metadata["fileName"]?.let { put("file", it) }

            // Code-specific
            metadata["gitCommitHash"]?.let { put("commit", it.take(8)) }
            metadata["className"]?.let { put("class", it) }
            metadata["methodName"]?.let { put("method", it) }
            metadata["language"]?.let { put("lang", it) }
        }
}
