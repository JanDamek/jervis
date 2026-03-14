package com.jervis.ui.meeting

import com.jervis.di.RpcConnectionManager
import com.jervis.di.RpcConnectionState
import com.jervis.dto.meeting.AudioChunkDto
import com.jervis.dto.meeting.AudioInputType
import com.jervis.dto.meeting.MeetingCreateDto
import com.jervis.dto.meeting.MeetingFinalizeDto
import com.jervis.dto.meeting.MeetingTypeEnum
import com.jervis.repository.JervisRepository
import com.jervis.ui.storage.AudioChunkQueue
import com.jervis.ui.storage.OfflineMeeting
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * Unified recording upload service — replaces OfflineMeetingSyncService.
 *
 * Runs continuously while the app is alive. Uploads audio chunks for ALL recording sessions
 * (no "online" vs "offline" distinction). Handles:
 * - Creating server meeting on first contact
 * - Uploading pending chunks from disk (4MB sub-chunks, idempotent)
 * - Finalizing after user stops AND all chunks uploaded (triggers transcription)
 * - Retry with backoff on failures
 * - Migration from old OfflineMeetingStorage + RecordingStateStorage on first run
 */
class RecordingUploadService(
    private val connectionManager: RpcConnectionManager,
    private val repository: JervisRepository,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, e ->
        if (e !is CancellationException) {
            println("RecordingUploadService: uncaught exception: ${e::class.simpleName}: ${e.message}")
        }
    })

    private val _sessions = MutableStateFlow<List<RecordingSession>>(emptyList())
    val sessions: StateFlow<List<RecordingSession>> = _sessions.asStateFlow()

    /** Emits server meeting ID when a session is finalized — MeetingViewModel listens to refresh list. */
    private val _sessionFinalized = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val sessionFinalized: SharedFlow<String> = _sessionFinalized

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    companion object {
        private const val CHUNK_SIZE_BYTES = 4 * 1024 * 1024
        private const val MAX_RETRIES = 5
        private const val POLL_INTERVAL_MS = 2_000L
    }

    init {
        migrateFromLegacyStorage()

        // Load and recover any sessions that were mid-upload when app terminated
        val loaded = RecordingSessionStorage.load()
        _sessions.value = loaded.filter { !it.finalized }

        // Continuous upload loop — polls every 5s + wakes on connection restore
        scope.launch {
            connectionManager.state
                .map { it is RpcConnectionState.Connected }
                .distinctUntilChanged()
                .collect { connected ->
                    if (connected) {
                        // Reset errors on reconnect so stuck sessions retry immediately
                        val all = RecordingSessionStorage.load()
                        val hasErrors = all.any { !it.finalized && it.error != null }
                        if (hasErrors) {
                            val reset = all.map { s ->
                                if (!s.finalized && s.error != null) s.copy(error = null, retryCount = 0) else s
                            }
                            RecordingSessionStorage.save(reset)
                            _sessions.value = reset.filter { !it.finalized }
                        }
                        runUploadCycle()
                    }
                }
        }
        scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                if (connectionManager.state.value is RpcConnectionState.Connected) {
                    runUploadCycle()
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
        if (_isSyncing.value) {
            println("[Upload] Skipping cycle — already syncing")
            return
        }
        _isSyncing.value = true

        try {
            val allSessions = RecordingSessionStorage.load()
            val sessions = allSessions.filter { !it.finalized && it.error == null }
            val errorSessions = allSessions.filter { !it.finalized && it.error != null }
            println("[Upload] Cycle start: ${sessions.size} active, ${errorSessions.size} with errors, ${allSessions.count { it.finalized }} finalized")

            // Upload all sessions concurrently
            coroutineScope {
                sessions.map { session ->
                    async {
                        println("[Upload] Processing session ${session.localId}: serverMeetingId=${session.serverMeetingId}, chunks=${session.uploadedChunkCount}/${session.chunkCount}, stopped=${session.stoppedAtMs != null}")
                        try {
                            uploadSession(session)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val msg = e.message ?: ""
                            if (msg.contains("not in recording") || msg.contains("INDEXED") || msg.contains("TRANSCRIBING") || msg.contains("DONE")) {
                                println("[Upload] Session ${session.localId} already processed on server, marking finalized: $msg")
                                AudioChunkQueue.clearMeeting(session.localId)
                                updateSession(session.localId) { it.copy(finalized = true, error = null) }
                                return@async
                            }
                            val newRetry = session.retryCount + 1
                            println("[Upload] FAILED session ${session.localId} (attempt $newRetry/$MAX_RETRIES): ${e::class.simpleName}: ${e.message}")
                            if (newRetry >= MAX_RETRIES) {
                                updateSession(session.localId) { it.copy(error = e.message ?: "Upload failed", retryCount = newRetry) }
                            } else {
                                updateSession(session.localId) { it.copy(retryCount = newRetry) }
                            }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            _isSyncing.value = false
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
            println("[Upload] Server created meeting: ${serverMeeting.id} for session ${session.localId}")
            updateSession(session.localId) { it.copy(serverMeetingId = serverMeeting.id) }
            serverMeeting.id
        }

        // 2. Upload pending chunks from disk
        val pendingChunks = AudioChunkQueue.getAllPending()
            .filter { it.meetingId == session.localId }
            .sortedBy { it.chunkIndex }

        var serverChunkIndex = session.uploadedChunkCount
        for (chunk in pendingChunks) {
            val rawData = AudioChunkQueue.readChunk(chunk) ?: continue

            // Upload in 4MB sub-chunks
            var offset = 0
            while (offset < rawData.size) {
                val end = minOf(offset + CHUNK_SIZE_BYTES, rawData.size)
                val subChunk = rawData.copyOfRange(offset, end)
                repository.meetings.uploadAudioChunk(
                    AudioChunkDto(
                        meetingId = serverMeetingId,
                        chunkIndex = serverChunkIndex,
                        data = Base64.encode(subChunk),
                    ),
                )
                serverChunkIndex++
                offset = end
            }
            AudioChunkQueue.dequeue(chunk)
            updateSession(session.localId) { it.copy(uploadedChunkCount = serverChunkIndex) }
        }

        // 3. Finalize if stopped and all chunks uploaded
        val current = RecordingSessionStorage.load().find { it.localId == session.localId } ?: return
        val allPending = AudioChunkQueue.getAllPending().filter { it.meetingId == session.localId }
        if (current.stoppedAtMs != null && allPending.isEmpty()) {
            println("[Upload] Finalizing session ${session.localId} → server meeting $serverMeetingId")
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
            println("[Upload] Session ${session.localId} finalized successfully")
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
            println("[Upload] Migrated ${migrated.size} sessions from legacy storage")
            RecordingSessionStorage.save(existingSessions + migrated)
        }
    }
}
