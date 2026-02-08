package com.jervis.ui.meeting

import com.jervis.dto.meeting.AudioChunkDto
import com.jervis.dto.meeting.AudioInputType
import com.jervis.dto.meeting.MeetingCreateDto
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.MeetingFinalizeDto
import com.jervis.dto.meeting.MeetingTypeEnum
import com.jervis.service.IMeetingService
import com.jervis.ui.audio.AudioRecorder
import com.jervis.ui.audio.AudioRecordingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class MeetingViewModel(
    private val meetingService: IMeetingService,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _meetings = MutableStateFlow<List<MeetingDto>>(emptyList())
    val meetings: StateFlow<List<MeetingDto>> = _meetings.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

    private val _currentMeetingId = MutableStateFlow<String?>(null)
    val currentMeetingId: StateFlow<String?> = _currentMeetingId.asStateFlow()

    private val _selectedMeeting = MutableStateFlow<MeetingDto?>(null)
    val selectedMeeting: StateFlow<MeetingDto?> = _selectedMeeting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var audioRecorder: AudioRecorder? = null
    private var chunkUploadJob: Job? = null
    private var durationUpdateJob: Job? = null
    private var chunkIndex = 0

    companion object {
        private const val CHUNK_INTERVAL_MS = 30_000L
    }

    fun loadMeetings(clientId: String, projectId: String? = null) {
        scope.launch {
            _isLoading.value = true
            try {
                val list = meetingService.listMeetings(clientId, projectId)
                _meetings.value = list
            } catch (e: Exception) {
                _error.value = "Nepodařilo se načíst schůzky: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun startRecording(
        clientId: String,
        projectId: String? = null,
        audioInputType: AudioInputType = AudioInputType.MIXED,
        recordingConfig: AudioRecordingConfig = AudioRecordingConfig(),
    ) {
        scope.launch {
            try {
                val meeting = meetingService.startRecording(
                    MeetingCreateDto(
                        clientId = clientId,
                        projectId = projectId,
                        audioInputType = audioInputType,
                    ),
                )
                _currentMeetingId.value = meeting.id

                val recorder = AudioRecorder()
                val started = recorder.startRecording(recordingConfig)
                if (!started) {
                    _error.value = "Nepodařilo se spustit nahrávání zvuku"
                    meetingService.cancelRecording(meeting.id)
                    _currentMeetingId.value = null
                    return@launch
                }

                audioRecorder = recorder
                _isRecording.value = true
                chunkIndex = 0

                startChunkUpload(meeting.id)
                startDurationUpdate()
            } catch (e: Exception) {
                _error.value = "Chyba při spouštění nahrávání: ${e.message}"
            }
        }
    }

    fun stopRecording() {
        scope.launch {
            chunkUploadJob?.cancel()
            durationUpdateJob?.cancel()
            _isRecording.value = false

            val recorder = audioRecorder ?: return@launch
            val meetingId = _currentMeetingId.value ?: return@launch

            try {
                // Get remaining audio data
                val remainingData = recorder.stopRecording()
                if (remainingData != null && remainingData.isNotEmpty()) {
                    uploadChunk(meetingId, remainingData, isLast = true)
                }
            } catch (e: Exception) {
                _error.value = "Chyba při zastavení nahrávání: ${e.message}"
            } finally {
                recorder.release()
                audioRecorder = null
            }
        }
    }

    fun finalizeRecording(
        title: String?,
        meetingType: MeetingTypeEnum,
    ) {
        val meetingId = _currentMeetingId.value ?: return
        val duration = _recordingDuration.value

        scope.launch {
            try {
                val result = meetingService.finalizeRecording(
                    MeetingFinalizeDto(
                        meetingId = meetingId,
                        title = title,
                        meetingType = meetingType,
                        durationSeconds = duration,
                    ),
                )
                _currentMeetingId.value = null
                _recordingDuration.value = 0

                // Refresh meetings list
                _meetings.value = _meetings.value.map { if (it.id == result.id) result else it } +
                    if (_meetings.value.none { it.id == result.id }) listOf(result) else emptyList()
            } catch (e: Exception) {
                _error.value = "Chyba při dokončování nahrávky: ${e.message}"
            }
        }
    }

    fun cancelRecording() {
        val meetingId = _currentMeetingId.value ?: return

        chunkUploadJob?.cancel()
        durationUpdateJob?.cancel()
        _isRecording.value = false
        _recordingDuration.value = 0

        audioRecorder?.release()
        audioRecorder = null

        scope.launch {
            try {
                meetingService.cancelRecording(meetingId)
            } catch (_: Exception) {
                // Ignore cancel errors
            }
            _currentMeetingId.value = null
        }
    }

    fun selectMeeting(meeting: MeetingDto?) {
        _selectedMeeting.value = meeting
    }

    fun refreshMeeting(meetingId: String) {
        scope.launch {
            try {
                val updated = meetingService.getMeeting(meetingId)
                _meetings.value = _meetings.value.map { if (it.id == meetingId) updated else it }
                if (_selectedMeeting.value?.id == meetingId) {
                    _selectedMeeting.value = updated
                }
            } catch (_: Exception) {
                // Ignore refresh errors
            }
        }
    }

    fun deleteMeeting(meetingId: String) {
        scope.launch {
            try {
                meetingService.deleteMeeting(meetingId)
                _meetings.value = _meetings.value.filter { it.id != meetingId }
                if (_selectedMeeting.value?.id == meetingId) {
                    _selectedMeeting.value = null
                }
            } catch (e: Exception) {
                _error.value = "Nepodařilo se smazat schůzku: ${e.message}"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun startChunkUpload(meetingId: String) {
        chunkUploadJob = scope.launch {
            while (_isRecording.value) {
                delay(CHUNK_INTERVAL_MS)
                if (!_isRecording.value) break

                val data = audioRecorder?.getAndClearBuffer() ?: continue
                if (data.isNotEmpty()) {
                    uploadChunk(meetingId, data, isLast = false)
                }
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun uploadChunk(meetingId: String, data: ByteArray, isLast: Boolean) {
        try {
            val base64Data = Base64.encode(data)
            meetingService.uploadAudioChunk(
                AudioChunkDto(
                    meetingId = meetingId,
                    chunkIndex = chunkIndex++,
                    data = base64Data,
                    isLast = isLast,
                ),
            )
        } catch (e: Exception) {
            _error.value = "Chyba při odesílání zvukového bloku: ${e.message}"
        }
    }

    private fun startDurationUpdate() {
        durationUpdateJob = scope.launch {
            while (_isRecording.value) {
                _recordingDuration.value = audioRecorder?.durationSeconds ?: 0
                delay(1000)
            }
        }
    }
}
