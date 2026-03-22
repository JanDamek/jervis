package com.jervis.ui.meeting

import com.jervis.di.RpcConnectionManager
import com.jervis.di.RpcConnectionState
import com.jervis.dto.meeting.AudioChunkDto
import com.jervis.dto.meeting.AudioInputType
import com.jervis.dto.meeting.MeetingCreateDto
import com.jervis.dto.meeting.MeetingFinalizeDto
import com.jervis.dto.meeting.MeetingTypeEnum
import com.jervis.repository.JervisRepository
import com.jervis.ui.util.platformLog
import com.jervis.ui.storage.AudioChunkQueue
import com.jervis.ui.storage.OfflineMeetingStorage
import com.jervis.ui.storage.OfflineSyncState
import com.jervis.ui.storage.RecordingSession
import com.jervis.ui.storage.RecordingSessionStorage
import com.jervis.ui.storage.RecordingStateStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Unified recording upload service.
 *
 * Runs continuously while the app is alive. Uploads audio chunks for ALL recording sessions.
 *
 * Design principles:
 * - Server is source of truth for chunkCount — client syncs before uploading
 * - Per-chunk error handling — one failed chunk doesn't kill the session
 * - Sequential sessions — no concurrent WebSocket contention
 * - Never gives up — always retries, user can only manually cancel
 * - Duplicate chunks = skip (not error), normal after reconnect
 */
class RecordingUploadService(
    private val connectionManager: RpcConnectionManager,
    private val repository: JervisRepository,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, e ->
        if (e !is CancellationException) {
            platformLog("Upload", "Uncaught exception: ${e::class.simpleName}: ${e.message}")
        }
    })

    private val _sessions = MutableStateFlow<List<RecordingSession>>(emptyList())
    val sessions: StateFlow<List<RecordingSession>> = _sessions.asStateFlow()

    /** Emits server meeting ID when a session is finalized — MeetingViewModel listens to refresh list. */
    private val _sessionFinalized = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val sessionFinalized: SharedFlow<String> = _sessionFinalized

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()
    private val uploadMutex = kotlinx.coroutines.sync.Mutex()

    companion object {
        private const val CHUNK_SIZE_BYTES = 4 * 1024 * 1024
        private const val POLL_INTERVAL_MS = 3_000L
        private const val CHUNK_ERROR_DELAY_MS = 2_000L
        private const val MAX_CONSECUTIVE_ERRORS = 10
    }

    init {
        migrateFromLegacyStorage()

        // Load and recover any sessions that were mid-upload when app terminated
        val loaded = RecordingSessionStorage.load()
        _sessions.value = loaded.filter { !it.finalized }

        // Wake on connection restore
        scope.launch {
            connectionManager.state
                .map { it is RpcConnectionState.Connected }
                .distinctUntilChanged()
                .collect { connected ->
                    if (connected) {
                        platformLog("Upload", "Connection restored — starting upload cycle")
                        try { runUploadCycle() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            platformLog("Upload", "Cycle failed: ${e::class.simpleName}: ${e.message}")
                        }
                    }
                }
        }
        // Continuous poll
        scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                if (connectionManager.state.value is RpcConnectionState.Connected) {
                    try { runUploadCycle() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        platformLog("Upload", "Cycle failed: ${e::class.simpleName}: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Register a new recording session. Called by MeetingViewModel.startRecording().
     */
    fun registerSession(session: RecordingSession) {
        val all = RecordingSessionStorage.load() + session
        RecordingSessionStorage.save(all)
        _sessions.value = all.filter { !it.finalized }
    }

    /**
     * Update a session (e.g., mark stopped, increment chunk count).
     * Returns the updated session.
     */
    fun updateSession(localId: String, transform: (RecordingSession) -> RecordingSession): RecordingSession? {
        var updated: RecordingSession? = null
        val all = RecordingSessionStorage.load().map { s ->
            if (s.localId == localId) {
                updated = transform(s)
                updated!!
            } else s
        }
        RecordingSessionStorage.save(all)
        _sessions.value = all.filter { !it.finalized }
        return updated
    }

    /**
     * Cancel a session: remove from storage and clean up disk chunks.
     */
    fun cancelSession(localId: String, serverMeetingId: String?) {
        AudioChunkQueue.clearMeeting(localId)
        val remaining = RecordingSessionStorage.load().filter { it.localId != localId }
        RecordingSessionStorage.save(remaining)
        _sessions.value = remaining.filter { !it.finalized }
        // Try to cancel on server if we had a server meeting
        if (serverMeetingId != null) {
            scope.launch {
                try {
                    repository.meetings.cancelRecording(serverMeetingId)
                } catch (_: Exception) {}
            }
        }
    }

    /** Retry a failed session (reset error + retry count). */
    fun retrySession(localId: String) {
        updateSession(localId) { it.copy(error = null, retryCount = 0) }
        scope.launch { runUploadCycle() }
    }

    // ── Upload cycle ────────────────────────────────────────────────────

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun runUploadCycle() {
        if (!uploadMutex.tryLock()) return // Another cycle running — skip
        _isSyncing.value = true

        try {
            val allSessions = RecordingSessionStorage.load()
            val activeSessions = allSessions.filter { !it.finalized }
            if (activeSessions.isEmpty()) return

            val connState = connectionManager.state.value
            platformLog("Upload", "Cycle: ${activeSessions.size} active sessions, connection=$connState")

            if (connState !is RpcConnectionState.Connected) {
                platformLog("Upload", "Not connected — skipping cycle")
                return
            }

            // Process each session sequentially
            for (session in activeSessions) {
                if (connectionManager.state.value !is RpcConnectionState.Connected) {
                    platformLog("Upload", "Connection lost mid-cycle — stopping")
                    break
                }

                // Check pending chunks on disk
                val pendingCount = AudioChunkQueue.getAllPending().count { it.meetingId == session.localId }

                // Detect orphaned sessions: chunks claimed but no files on disk
                // This happens when app is reinstalled (files deleted) or crashed during recording
                if (pendingCount == 0 && session.chunkCount > 0 && session.uploadedChunkCount < session.chunkCount) {
                    platformLog("Upload", "Session ${session.localId}: orphaned — claims ${session.chunkCount} chunks " +
                        "but 0 on disk (files lost after reinstall?). Finalizing with what server has.")
                    if (session.serverMeetingId != null) {
                        try {
                            val meetingType = session.meetingType?.let {
                                try { MeetingTypeEnum.valueOf(it) } catch (_: Exception) { null }
                            }
                            repository.meetings.finalizeRecording(
                                MeetingFinalizeDto(
                                    meetingId = session.serverMeetingId,
                                    title = session.title,
                                    meetingType = meetingType ?: MeetingTypeEnum.MEETING,
                                    durationSeconds = session.durationSeconds,
                                ),
                            )
                        } catch (_: Exception) { /* server may not have this meeting */ }
                    }
                    updateSession(session.localId) { it.copy(finalized = true) }
                    continue
                }

                // Skip sessions that are still recording and have no pending chunks yet
                if (session.stoppedAtMs == null && pendingCount == 0) {
                    continue
                }

                platformLog("Upload", "Processing session ${session.localId}: " +
                    "server=${session.serverMeetingId}, chunks=${session.uploadedChunkCount}/${session.chunkCount}, " +
                    "pending=$pendingCount, stopped=${session.stoppedAtMs != null}")

                try {
                    uploadSession(session)
                } catch (e: Throwable) {
                    // Catch Throwable — kRPC DeserializedException may extend CancellationException
                    // Only rethrow real cancellation, not kRPC server errors disguised as CancellationException
                    if (e is CancellationException && e::class.simpleName == "CancellationException") {
                        throw e
                    }
                    val msg = e.message ?: ""
                    // Server meeting deleted — clear serverMeetingId, next cycle will create new one
                    if (msg.contains("not found", ignoreCase = true)) {
                        platformLog("Upload", "Session ${session.localId}: server meeting gone — will re-create next cycle")
                        updateSession(session.localId) { it.copy(serverMeetingId = null, uploadedChunkCount = 0) }
                    // Server says meeting is already processed — mark as done
                    } else if (msg.contains("not in recording") || msg.contains("INDEXED") ||
                        msg.contains("TRANSCRIBING") || msg.contains("DONE") ||
                        msg.contains("FAILED")
                    ) {
                        platformLog("Upload", "Session ${session.localId} already processed: $msg — clearing")
                        AudioChunkQueue.clearMeeting(session.localId)
                        updateSession(session.localId) { it.copy(finalized = true, error = null) }
                    } else {
                        // Session-level error — log and continue to next session
                        platformLog("Upload", "Session ${session.localId} error: ${e::class.simpleName}: $msg")
                    }
                }
            }
        } finally {
            _isSyncing.value = false
            uploadMutex.unlock()
            _sessions.value = RecordingSessionStorage.load().filter { !it.finalized }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun uploadSession(session: RecordingSession) {
        val audioInputType = try {
            AudioInputType.valueOf(session.audioInputType)
        } catch (_: Exception) {
            AudioInputType.MIXED
        }
        val meetingType = session.meetingType?.let {
            try { MeetingTypeEnum.valueOf(it) } catch (_: Exception) { null }
        }

        // 1. Ensure server meeting exists
        val serverMeetingId = if (session.serverMeetingId != null) {
            session.serverMeetingId
        } else {
            val serverMeeting = repository.meetings.startRecording(
                MeetingCreateDto(
                    clientId = session.clientId,
                    projectId = session.projectId,
                    audioInputType = audioInputType,
                    title = session.title,
                    meetingType = meetingType,
                ),
            )
            platformLog("Upload", "Server created meeting: ${serverMeeting.id} for session ${session.localId}")
            updateSession(session.localId) { it.copy(serverMeetingId = serverMeeting.id) }
            serverMeeting.id
        }

        // 2. Upload pending chunks from disk — PER-CHUNK error handling
        val pendingChunks = AudioChunkQueue.getAllPending()
            .filter { it.meetingId == session.localId }
            .sortedBy { it.chunkIndex }

        if (pendingChunks.isEmpty()) {
            // No pending chunks — check if we should finalize
            tryFinalize(session, serverMeetingId, meetingType)
            return
        }

        var serverChunkIndex = session.uploadedChunkCount
        var consecutiveErrors = 0
        var chunksUploaded = 0

        for (chunk in pendingChunks) {
            // Check connection before each chunk
            if (connectionManager.state.value !is RpcConnectionState.Connected) {
                platformLog("Upload", "Connection lost during upload — pausing session ${session.localId}")
                break
            }

            // Too many consecutive errors — pause this session for this cycle
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                platformLog("Upload", "${session.localId}: $consecutiveErrors consecutive errors — pausing for this cycle")
                break
            }

            val rawData = AudioChunkQueue.readChunk(chunk)
            if (rawData == null) {
                // Chunk file missing from disk — skip it, dequeue the orphan
                platformLog("Upload", "${session.localId}: chunk ${chunk.chunkIndex} missing from disk — skipping")
                AudioChunkQueue.dequeue(chunk)
                continue
            }

            // Upload in 4MB sub-chunks
            var offset = 0
            var subChunkFailed = false
            while (offset < rawData.size) {
                val end = minOf(offset + CHUNK_SIZE_BYTES, rawData.size)
                val subChunk = rawData.copyOfRange(offset, end)

                try {
                    val newServerCount = repository.meetings.uploadAudioChunk(
                        AudioChunkDto(
                            meetingId = serverMeetingId,
                            chunkIndex = serverChunkIndex,
                            data = Base64.encode(subChunk),
                        ),
                    )
                    // Server returns its chunkCount — sync our state
                    serverChunkIndex = newServerCount
                    consecutiveErrors = 0
                    offset = end
                } catch (e: Throwable) {
                    if (e is CancellationException && e::class.simpleName == "CancellationException") throw e
                    val errMsg = e.message ?: ""
                    // Server meeting gone — propagate to per-session handler to re-create
                    if (errMsg.contains("not found", ignoreCase = true)) throw e
                    consecutiveErrors++
                    platformLog("Upload", "${session.localId}: chunk ${chunk.chunkIndex} sub-chunk failed " +
                        "(error $consecutiveErrors/$MAX_CONSECUTIVE_ERRORS): ${e::class.simpleName}: $errMsg")
                    subChunkFailed = true
                    delay(CHUNK_ERROR_DELAY_MS)
                    break
                }
            }

            if (!subChunkFailed) {
                // Entire disk chunk uploaded successfully — remove from queue
                AudioChunkQueue.dequeue(chunk)
                chunksUploaded++
            }

            // Persist progress after each chunk
            updateSession(session.localId) { it.copy(uploadedChunkCount = serverChunkIndex) }
        }

        if (chunksUploaded > 0) {
            platformLog("Upload", "${session.localId}: uploaded $chunksUploaded chunks this cycle (server has $serverChunkIndex)")
        }

        // 3. Check if ready to finalize
        tryFinalize(session, serverMeetingId, meetingType)
    }

    private suspend fun tryFinalize(session: RecordingSession, serverMeetingId: String, meetingType: MeetingTypeEnum?) {
        val current = RecordingSessionStorage.load().find { it.localId == session.localId } ?: return
        val allPending = AudioChunkQueue.getAllPending().filter { it.meetingId == session.localId }
        if (current.stoppedAtMs != null && allPending.isEmpty()) {
            platformLog("Upload", "Finalizing session ${session.localId} → server meeting $serverMeetingId")
            repository.meetings.finalizeRecording(
                MeetingFinalizeDto(
                    meetingId = serverMeetingId,
                    title = current.title,
                    meetingType = meetingType ?: MeetingTypeEnum.MEETING,
                    durationSeconds = current.durationSeconds,
                ),
            )
            AudioChunkQueue.clearMeeting(session.localId)
            updateSession(session.localId) { it.copy(finalized = true) }
            platformLog("Upload", "Session ${session.localId} finalized successfully")
            _sessionFinalized.tryEmit(serverMeetingId)
        }
    }

    // ── Migration from legacy storage ───────────────────────────────────

    private fun migrateFromLegacyStorage() {
        // Migrate OfflineMeeting entries
        val offlineMeetings = try {
            OfflineMeetingStorage.load()
        } catch (_: Exception) {
            emptyList()
        }
        // Migrate RecordingState entries
        val recordingStates = try {
            RecordingStateStorage.loadAll()
        } catch (_: Exception) {
            emptyList()
        }

        if (offlineMeetings.isEmpty() && recordingStates.isEmpty()) return

        val existingSessions = RecordingSessionStorage.load()
        val existingIds = existingSessions.map { it.localId }.toSet()
        val migrated = mutableListOf<RecordingSession>()

        // Convert OfflineMeetings
        for (m in offlineMeetings) {
            if (m.localId in existingIds) continue
            if (m.syncState == OfflineSyncState.SYNCED) continue
            migrated.add(
                RecordingSession(
                    localId = m.localId,
                    clientId = m.clientId,
                    projectId = m.projectId,
                    title = m.title,
                    meetingType = m.meetingType,
                    audioInputType = m.audioInputType,
                    startedAtMs = m.startedAtMs,
                    stoppedAtMs = m.stoppedAtMs,
                    durationSeconds = m.durationSeconds,
                    chunkCount = m.chunkCount,
                    serverMeetingId = m.serverMeetingId,
                    uploadedChunkCount = m.uploadedChunks,
                    error = if (m.syncState == OfflineSyncState.FAILED) m.syncError else null,
                    retryCount = m.retryCount,
                ),
            )
        }

        // Convert RecordingStates (interrupted online recordings)
        for (s in recordingStates) {
            if (s.meetingId in existingIds) continue
            migrated.add(
                RecordingSession(
                    localId = s.meetingId,
                    clientId = s.clientId,
                    projectId = s.projectId,
                    title = s.title,
                    meetingType = s.meetingType,
                    audioInputType = "MIXED",
                    startedAtMs = s.startedAtMs,
                    stoppedAtMs = s.startedAtMs, // Treat as stopped (app crashed)
                    serverMeetingId = s.meetingId, // Was online — meetingId IS the server ID
                    uploadedChunkCount = s.chunkIndex,
                ),
            )
        }

        if (migrated.isNotEmpty()) {
            platformLog("Upload", "Migrated ${migrated.size} sessions from legacy storage")
            RecordingSessionStorage.save(existingSessions + migrated)
        }
    }
}
