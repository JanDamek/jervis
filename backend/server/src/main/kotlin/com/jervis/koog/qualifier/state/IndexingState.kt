package com.jervis.koog.qualifier.state

/**
 * Indexing state tracking the base node and chunk processing progress.
 *
 * This state is built from ExtractionResult and used for unified indexing (Phase 3).
 */
data class IndexingState(
    val baseNodeKey: String,
    val baseInfo: String, // Summary for base node
    val chunks: List<String>, // Indexable chunks
    val processedChunkCount: Int = 0,
    val createdBaseNode: Boolean = false,
    val errors: List<String> = emptyList(),
) {
    fun hasChunks(): Boolean = chunks.isNotEmpty()

    fun allChunksProcessed(): Boolean = processedChunkCount >= chunks.size

    fun withError(error: String): IndexingState = copy(errors = errors + error)

    fun withBaseNodeCreated(): IndexingState = copy(createdBaseNode = true)

    fun withProcessedCount(count: Int): IndexingState = copy(processedChunkCount = count)

    companion object {
        fun empty(): IndexingState =
            IndexingState(
                baseNodeKey = "",
                baseInfo = "",
                chunks = emptyList(),
            )
    }
}
