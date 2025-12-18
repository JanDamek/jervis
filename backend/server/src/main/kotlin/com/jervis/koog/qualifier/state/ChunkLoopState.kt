package com.jervis.koog.qualifier.state

/**
 * Wrapper state for chunk processing loop (subgraph).
 *
 * This type preserves the entire QualifierPipelineState while iterating through chunks.
 * It prevents "dummy state" anti-pattern by maintaining full context.
 *
 * Koog best practice: Subgraph state must be explicitly typed to avoid loss of context.
 */
data class ChunkLoopState(
    val pipeline: QualifierPipelineState, // Full pipeline state (NEVER lost!)
    val currentIndex: Int,
) {
    /**
     * Check if there are more chunks to process.
     */
    fun hasMore(): Boolean = currentIndex < pipeline.indexing.chunks.size

    /**
     * Get the next chunk to process.
     */
    fun nextChunk(): String = pipeline.indexing.chunks[currentIndex]

    /**
     * Advance to the next chunk.
     */
    fun advance(): ChunkLoopState = copy(currentIndex = currentIndex + 1)

    /**
     * Update the underlying pipeline state (e.g., after tool execution).
     */
    fun updatePipeline(updater: (QualifierPipelineState) -> QualifierPipelineState): ChunkLoopState =
        copy(pipeline = updater(pipeline))
}
