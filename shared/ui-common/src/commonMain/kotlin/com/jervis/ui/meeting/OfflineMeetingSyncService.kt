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
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Automatically syncs offline meetings to the server when connection is restored.
 *
 * Watches [RpcConnectionManager.state] and, when Connected, uploads all
 * PENDING/FAILED offline meetings: creates server meeting, uploads audio chunks,
 * and finalizes.
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
    }

    init {
        _pendingMeetings.value = OfflineMeetingStorage.load()
            .filter { it.syncState != OfflineSyncState.SYNCED }

        // Watch connection state — sync when connected
        scope.launch {
            connectionManager.state.collect { state ->
                if (state is RpcConnectionState.Connected) {
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
                val meetings = OfflineMeetingStorage.load()
                    .filter { it.syncState == OfflineSyncState.PENDING || it.syncState == OfflineSyncState.FAILED }

                for (meeting in meetings) {
                    syncMeeting(meeting)
                    // Brief delay between meetings to avoid overwhelming the server
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
        println("[OfflineSync] Syncing offline meeting ${meeting.localId}")
        updateMeetingState(meeting.localId, OfflineSyncState.SYNCING, null)

        try {
            // 1. Create meeting on server
            val audioInputType = try {
                AudioInputType.valueOf(meeting.audioInputType)
            } catch (_: Exception) {
                AudioInputType.MIXED
            }
            val meetingType = meeting.meetingType?.let {
                try { MeetingTypeEnum.valueOf(it) } catch (_: Exception) { null }
            }

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

            // 2. Upload all disk chunks
            val pendingChunks = AudioChunkQueue.getAllPending()
                .filter { it.meetingId == meeting.localId }
                .sortedBy { it.chunkIndex }

            var chunkIndex = 0
            for (chunk in pendingChunks) {
                val rawData = AudioChunkQueue.readChunk(chunk) ?: continue

                // Upload in sub-chunks (same pattern as online upload)
                var offset = 0
                while (offset < rawData.size) {
                    val end = minOf(offset + CHUNK_SIZE_BYTES, rawData.size)
                    val subChunk = rawData.copyOfRange(offset, end)
                    repository.meetings.uploadAudioChunk(
                        AudioChunkDto(
                            meetingId = serverMeeting.id,
                            chunkIndex = chunkIndex,
                            data = Base64.encode(subChunk),
                        ),
                    )
                    chunkIndex++
                    offset = end
                }
                AudioChunkQueue.dequeue(chunk)
            }

            // 3. Finalize
            repository.meetings.finalizeRecording(
                MeetingFinalizeDto(
                    meetingId = serverMeeting.id,
                    title = meeting.title,
                    meetingType = meetingType ?: MeetingTypeEnum.MEETING,
                    durationSeconds = meeting.durationSeconds,
                ),
            )

            // 4. Mark as synced
            AudioChunkQueue.clearMeeting(meeting.localId)
            updateMeetingState(meeting.localId, OfflineSyncState.SYNCED, null)
            println("[OfflineSync] Meeting ${meeting.localId} synced as ${serverMeeting.id}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("[OfflineSync] Failed to sync meeting ${meeting.localId}: ${e.message}")
            updateMeetingState(meeting.localId, OfflineSyncState.FAILED, e.message)
        }
    }

    private fun updateMeetingState(localId: String, state: OfflineSyncState, error: String?) {
        val all = OfflineMeetingStorage.load().map { m ->
            if (m.localId == localId) m.copy(syncState = state, syncError = error) else m
        }
        OfflineMeetingStorage.save(all)
        _pendingMeetings.value = all.filter { it.syncState != OfflineSyncState.SYNCED }
    }

    /** Retry a single failed meeting sync. */
    fun retryMeeting(localId: String) {
        updateMeetingState(localId, OfflineSyncState.PENDING, null)
        scope.launch {
            val meeting = OfflineMeetingStorage.load().find { it.localId == localId } ?: return@launch
            syncMeeting(meeting)
            _pendingMeetings.value = OfflineMeetingStorage.load()
                .filter { it.syncState != OfflineSyncState.SYNCED }
        }
    }
}
