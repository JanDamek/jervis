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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Automatically syncs offline meetings to the server when connection is restored.
 *
 * Watches [RpcConnectionManager.state] and, when Connected, uploads all
 * PENDING offline meetings: creates server meeting, uploads audio chunks,
 * and finalizes.
 *
 * Retry policy: max [MAX_RETRIES] attempts per meeting. After that,
 * the meeting stays FAILED and requires manual [retryMeeting] call.
 */
class OfflineMeetingSyncService(
    private val connectionManager: RpcConnectionManager,
    private val repository: JervisRepository,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _pendingMeetings = MutableStateFlow<List<OfflineMeeting>>(emptyList())
    val pendingMeetings: StateFlow<List<OfflineMeeting>> = _pendingMeetings.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    companion object {
        private const val CHUNK_SIZE_BYTES = 4 * 1024 * 1024
        private const val MAX_RETRIES = 3
    }

    init {
        // Recovery: reset SYNCING → PENDING (interrupted uploads from previous session)
        val loaded = OfflineMeetingStorage.load()
        val needsRecovery = loaded.any { it.syncState == OfflineSyncState.SYNCING }
        if (needsRecovery) {
            val recovered = loaded.map { m ->
                if (m.syncState == OfflineSyncState.SYNCING) m.copy(syncState = OfflineSyncState.PENDING) else m
            }
            OfflineMeetingStorage.save(recovered)
            _pendingMeetings.value = recovered.filter { it.syncState != OfflineSyncState.SYNCED }
        } else {
            _pendingMeetings.value = loaded.filter { it.syncState != OfflineSyncState.SYNCED }
        }

        // Watch connection state — sync ONLY on Connected transitions (deduplicated)
        scope.launch {
            connectionManager.state
                .map { it is RpcConnectionState.Connected }
                .distinctUntilChanged()
                .collect { connected ->
                    if (connected) {
                        syncPendingMeetings()
                    }
                }
        }
    }

    private fun syncPendingMeetings() {
        if (_isSyncing.value) return
        _isSyncing.value = true

        scope.launch {
            try {
                // Only sync PENDING meetings (not FAILED — those need manual retry)
                val meetings = OfflineMeetingStorage.load()
                    .filter { it.syncState == OfflineSyncState.PENDING }

                for (meeting in meetings) {
                    syncMeeting(meeting)
                    delay(1000)
                }
            } finally {
                _isSyncing.value = false
                _pendingMeetings.value = OfflineMeetingStorage.load()
                    .filter { it.syncState != OfflineSyncState.SYNCED }
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun syncMeeting(meeting: OfflineMeeting) {
        println("[OfflineSync] Syncing offline meeting ${meeting.localId} (attempt ${meeting.retryCount + 1}/$MAX_RETRIES)")
        updateMeetingState(meeting.localId, OfflineSyncState.SYNCING, null)

        try {
            val audioInputType = try {
                AudioInputType.valueOf(meeting.audioInputType)
            } catch (_: Exception) {
                AudioInputType.MIXED
            }
            val meetingType = meeting.meetingType?.let {
                try { MeetingTypeEnum.valueOf(it) } catch (_: Exception) { null }
            }

            // 1. Create meeting on server (or reuse existing from previous attempt)
            val serverMeetingId = if (meeting.serverMeetingId != null) {
                println("[OfflineSync] Resuming upload for server meeting: ${meeting.serverMeetingId}")
                meeting.serverMeetingId
            } else {
                val serverMeeting = repository.meetings.startRecording(
                    MeetingCreateDto(
                        clientId = meeting.clientId,
                        projectId = meeting.projectId,
                        audioInputType = audioInputType,
                        title = meeting.title,
                        meetingType = meetingType,
                    ),
                )
                println("[OfflineSync] Server created meeting: ${serverMeeting.id}")
                // Persist server ID immediately so we can resume on failure
                updateMeetingField(meeting.localId) { it.copy(serverMeetingId = serverMeeting.id) }
                serverMeeting.id
            }

            // 2. Upload disk chunks (skip already uploaded ones)
            val pendingChunks = AudioChunkQueue.getAllPending()
                .filter { it.meetingId == meeting.localId }
                .sortedBy { it.chunkIndex }

            var chunkIndex = meeting.uploadedChunks
            for (chunk in pendingChunks) {
                val rawData = AudioChunkQueue.readChunk(chunk) ?: continue

                // Upload in sub-chunks (same pattern as online upload)
                var offset = 0
                while (offset < rawData.size) {
                    val end = minOf(offset + CHUNK_SIZE_BYTES, rawData.size)
                    val subChunk = rawData.copyOfRange(offset, end)
                    repository.meetings.uploadAudioChunk(
                        AudioChunkDto(
                            meetingId = serverMeetingId,
                            chunkIndex = chunkIndex,
                            data = Base64.encode(subChunk),
                        ),
                    )
                    chunkIndex++
                    offset = end
                }
                AudioChunkQueue.dequeue(chunk)
                // Persist progress after each disk chunk
                updateMeetingField(meeting.localId) { it.copy(uploadedChunks = chunkIndex) }
            }

            // 3. Finalize
            repository.meetings.finalizeRecording(
                MeetingFinalizeDto(
                    meetingId = serverMeetingId,
                    title = meeting.title,
                    meetingType = meetingType ?: MeetingTypeEnum.MEETING,
                    durationSeconds = meeting.durationSeconds,
                ),
            )

            // 4. Mark as synced
            AudioChunkQueue.clearMeeting(meeting.localId)
            updateMeetingState(meeting.localId, OfflineSyncState.SYNCED, null)
            println("[OfflineSync] Meeting ${meeting.localId} synced as $serverMeetingId")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val newRetryCount = meeting.retryCount + 1
            println("[OfflineSync] Failed to sync meeting ${meeting.localId} (attempt $newRetryCount/$MAX_RETRIES): ${e.message}")
            updateMeetingState(meeting.localId, OfflineSyncState.FAILED, e.message, newRetryCount)
        }
    }

    private fun updateMeetingState(localId: String, state: OfflineSyncState, error: String?, retryCount: Int? = null) {
        updateMeetingField(localId) {
            it.copy(
                syncState = state,
                syncError = error,
                retryCount = retryCount ?: it.retryCount,
            )
        }
    }

    /** Update a single meeting in storage by applying a transform. */
    private fun updateMeetingField(localId: String, transform: (OfflineMeeting) -> OfflineMeeting) {
        val all = OfflineMeetingStorage.load().map { m ->
            if (m.localId == localId) transform(m) else m
        }
        OfflineMeetingStorage.save(all)
        _pendingMeetings.value = all.filter { it.syncState != OfflineSyncState.SYNCED }
    }

    /** Retry a single failed meeting sync (resets retry counter). */
    fun retryMeeting(localId: String) {
        updateMeetingState(localId, OfflineSyncState.PENDING, null, retryCount = 0)
        scope.launch {
            val meeting = OfflineMeetingStorage.load().find { it.localId == localId } ?: return@launch
            syncMeeting(meeting)
            _pendingMeetings.value = OfflineMeetingStorage.load()
                .filter { it.syncState != OfflineSyncState.SYNCED }
        }
    }

    /** Delete an offline meeting permanently (removes metadata + audio chunks). */
    fun deleteMeeting(localId: String) {
        AudioChunkQueue.clearMeeting(localId)
        val remaining = OfflineMeetingStorage.load().filter { it.localId != localId }
        OfflineMeetingStorage.save(remaining)
        _pendingMeetings.value = remaining.filter { it.syncState != OfflineSyncState.SYNCED }
    }
}
