package com.jervis.ui.storage

import kotlinx.serialization.Serializable

/**
 * Metadata for a meeting recorded while offline.
 * Audio chunks are stored separately via [AudioChunkQueue].
 */
@Serializable
data class OfflineMeeting(
    val localId: String,
    val clientId: String? = null,
    val projectId: String?,
    val title: String?,
    val meetingType: String?,
    val audioInputType: String,
    val startedAtMs: Long,
    val stoppedAtMs: Long? = null,
    val durationSeconds: Long = 0,
    val chunkCount: Int = 0,
    val syncState: OfflineSyncState = OfflineSyncState.PENDING,
    val syncError: String? = null,
)

@Serializable
enum class OfflineSyncState { PENDING, SYNCING, SYNCED, FAILED }

/**
 * Platform-specific persistent storage for offline meeting metadata.
 */
expect object OfflineMeetingStorage {
    fun save(meetings: List<OfflineMeeting>)
    fun load(): List<OfflineMeeting>
}
