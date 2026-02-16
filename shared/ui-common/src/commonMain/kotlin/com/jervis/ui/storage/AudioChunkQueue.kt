package com.jervis.ui.storage

import kotlinx.serialization.Serializable

/**
 * Metadata for a pending audio chunk saved to disk.
 */
@Serializable
data class PendingChunk(
    val meetingId: String,
    val chunkIndex: Int,
    val fileName: String,
    val createdAtMs: Long,
)

/**
 * Platform-specific disk queue for audio chunks.
 * Persists raw audio data to disk before upload so it survives app crashes.
 * After successful upload, the chunk is removed from disk.
 */
expect object AudioChunkQueue {
    /** Save raw audio bytes to disk, return PendingChunk metadata. */
    fun enqueue(meetingId: String, chunkIndex: Int, data: ByteArray): PendingChunk

    /** Read audio bytes for a pending chunk. */
    fun readChunk(chunk: PendingChunk): ByteArray?

    /** Remove a successfully uploaded chunk from disk. */
    fun dequeue(chunk: PendingChunk)

    /** Get all pending chunks (for retry after restart). */
    fun getAllPending(): List<PendingChunk>

    /** Remove all chunks for a meeting (after finalize/cancel). */
    fun clearMeeting(meetingId: String)
}
