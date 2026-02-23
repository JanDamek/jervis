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
 *
 * Stores a LIST of states so that a new recording doesn't overwrite
 * a crashed one. save(null) with meetingId removes one entry;
 * save(null) without meetingId is legacy compat (clears all — avoided).
 */
expect object RecordingStateStorage {
    /** Save or update state for a specific meetingId. Pass null to clear ALL. */
    fun save(state: RecordingState?)

    /** Load the first (oldest) stored state, or null. Legacy single-slot compat. */
    fun load(): RecordingState?

    /** Load all stored states. */
    fun loadAll(): List<RecordingState>

    /** Remove state for a specific meetingId. */
    fun remove(meetingId: String)
}
