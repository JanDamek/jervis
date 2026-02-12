package com.jervis.ui.storage

import kotlinx.serialization.Serializable

/**
 * Persisted recording state for crash/restart recovery.
 * Saved when recording starts, updated on each chunk upload, cleared on finalize/cancel.
 */
@Serializable
data class RecordingState(
    val meetingId: String,
    val clientId: String,
    val projectId: String?,
    val chunkIndex: Int,
    val title: String?,
    val meetingType: String?,
    val startedAtMs: Long,
)

/**
 * Platform-specific storage for recording state.
 * Survives app restarts — enables resuming interrupted uploads.
 */
expect object RecordingStateStorage {
    fun save(state: RecordingState?)
    fun load(): RecordingState?
}
