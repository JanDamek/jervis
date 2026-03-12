package com.jervis.ui.storage

import kotlinx.serialization.Serializable

/**
 * Unified recording session metadata — replaces both [RecordingState] and [OfflineMeeting].
 *
 * Every recording is local-first: audio chunks are always saved to disk via [AudioChunkQueue],
 * and uploaded asynchronously by RecordingUploadService. There is no "online" vs "offline" split.
 */
@Serializable
data class RecordingSession(
    /** Local ID generated at recording start: "rec_{uuid}" */
    val localId: String,
    val clientId: String? = null,
    val projectId: String? = null,
    val title: String? = null,
    val meetingType: String? = null,
    val audioInputType: String = "MIXED",
    val startedAtMs: Long,
    /** Set when user stops recording. null = still recording. */
    val stoppedAtMs: Long? = null,
    val durationSeconds: Long = 0,
    /** Total chunks saved to disk (incremented by chunk save job). */
    val chunkCount: Int = 0,
    /** Server-assigned meeting ID. Set after first successful server contact. */
    val serverMeetingId: String? = null,
    /** Number of chunks successfully uploaded to server. */
    val uploadedChunkCount: Int = 0,
    /** True after server finalize has been called (transcription triggered). */
    val finalized: Boolean = false,
    val error: String? = null,
    val retryCount: Int = 0,
)

/**
 * Platform-specific persistent storage for recording sessions.
 * JSON list, survives app restarts.
 */
expect object RecordingSessionStorage {
    fun save(sessions: List<RecordingSession>)
    fun load(): List<RecordingSession>
}
