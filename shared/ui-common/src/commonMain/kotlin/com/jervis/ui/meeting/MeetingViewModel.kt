package com.jervis.ui.meeting

import com.jervis.dto.ProjectDto
import com.jervis.dto.meeting.AudioChunkDto
import com.jervis.dto.meeting.AudioInputType
import com.jervis.dto.meeting.MeetingCreateDto
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.MeetingFinalizeDto
import com.jervis.dto.meeting.CorrectionAnswerDto
import com.jervis.dto.meeting.MeetingTypeEnum
import com.jervis.dto.meeting.TranscriptCorrectionSubmitDto
import com.jervis.service.IMeetingService
import com.jervis.service.IProjectService
import com.jervis.ui.audio.AudioPlayer
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
    private val projectService: IProjectService,
    internal val correctionService: com.jervis.service.ITranscriptCorrectionService? = null,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _meetings = MutableStateFlow<List<MeetingDto>>(emptyList())
    val meetings: StateFlow<List<MeetingDto>> = _meetings.asStateFlow()

    private val _projects = MutableStateFlow<List<ProjectDto>>(emptyList())
    val projects: StateFlow<List<ProjectDto>> = _projects.asStateFlow()

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

    private val _playingMeetingId = MutableStateFlow<String?>(null)
    val playingMeetingId: StateFlow<String?> = _playingMeetingId.asStateFlow()

    /** Set to true after audio upload succeeds, signals the UI to show finalize dialog */
    private val _readyToFinalize = MutableStateFlow(false)
    val readyToFinalize: StateFlow<Boolean> = _readyToFinalize.asStateFlow()

    private val _isCorrecting = MutableStateFlow(false)
    val isCorrecting: StateFlow<Boolean> = _isCorrecting.asStateFlow()

    private val _deletedMeetings = MutableStateFlow<List<MeetingDto>>(emptyList())
    val deletedMeetings: StateFlow<List<MeetingDto>> = _deletedMeetings.asStateFlow()

    private var audioRecorder: AudioRecorder? = null
    private var audioPlayer: AudioPlayer? = null
    private var durationUpdateJob: Job? = null
    private var playbackMonitorJob: Job? = null
    private var chunkIndex = 0

    private var lastClientId: String? = null
    private var lastProjectId: String? = null

    fun loadMeetings(clientId: String, projectId: String? = null) {
        lastClientId = clientId
        lastProjectId = projectId
        scope.launch {
            _isLoading.value = true
            try {
                _meetings.value = meetingService.listMeetings(clientId, projectId)
            } catch (e: Exception) {
                _error.value = "Nepodařilo se načíst schůzky: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadProjects(clientId: String) {
        scope.launch {
            try {
                _projects.value = projectService.listProjectsForClient(clientId)
            } catch (_: Exception) {
                _projects.value = emptyList()
            }
        }
    }

    fun startRecording(
        clientId: String,
        projectId: String? = null,
        audioInputType: AudioInputType = AudioInputType.MIXED,
        recordingConfig: AudioRecordingConfig = AudioRecordingConfig(),
    ) {
        lastClientId = clientId
        lastProjectId = projectId
        scope.launch {
            try {
                println("[Meeting] Starting recording for client=$clientId project=$projectId")
                val meeting = meetingService.startRecording(
                    MeetingCreateDto(
                        clientId = clientId,
                        projectId = projectId,
                        audioInputType = audioInputType,
                    ),
                )
                _currentMeetingId.value = meeting.id
                println("[Meeting] Server created meeting: ${meeting.id}")

                val recorder = AudioRecorder()
                val started = recorder.startRecording(recordingConfig)
                if (!started) {
                    println("[Meeting] AudioRecorder failed to start")
                    _error.value = "Nepodařilo se spustit nahrávání zvuku"
                    meetingService.cancelRecording(meeting.id)
                    _currentMeetingId.value = null
                    return@launch
                }

                audioRecorder = recorder
                _isRecording.value = true
                _readyToFinalize.value = false
                chunkIndex = 0

                println("[Meeting] Recording started successfully")
                startDurationUpdate()
            } catch (e: Exception) {
                println("[Meeting] Error starting recording: ${e.message}")
                _error.value = "Chyba při spouštění nahrávání: ${e.message}"
            }
        }
    }

    fun stopRecording() {
        // Guard against double-stop: grab and clear state synchronously
        if (!_isRecording.value) return
        _isRecording.value = false
        durationUpdateJob?.cancel()

        val recorder = audioRecorder ?: return
        audioRecorder = null // Clear immediately so second call returns at guard

        val meetingId = _currentMeetingId.value ?: run {
            recorder.release()
            return
        }

        scope.launch {
            println("[Meeting] Stopping recording for meeting=$meetingId")
            try {
                val audioData = recorder.stopRecording()
                println("[Meeting] AudioRecorder returned ${audioData?.size ?: 0} bytes")

                if (audioData != null && audioData.size > 44) {
                    val uploaded = uploadInChunks(meetingId, audioData)
                    if (uploaded) {
                        println("[Meeting] Upload succeeded, ready to finalize")
                        _readyToFinalize.value = true
                    } else {
                        println("[Meeting] Upload failed, cancelling meeting")
                        try { meetingService.cancelRecording(meetingId) } catch (_: Exception) {}
                        _currentMeetingId.value = null
                    }
                } else {
                    println("[Meeting] No audio data captured (${audioData?.size ?: 0} bytes), cancelling")
                    _error.value = "Nahrávání nezachytilo žádná data"
                    try { meetingService.cancelRecording(meetingId) } catch (_: Exception) {}
                    _currentMeetingId.value = null
                }
            } catch (e: Exception) {
                println("[Meeting] Error in stopRecording: ${e.message}")
                e.printStackTrace()
                _error.value = "Chyba při zastavení nahrávání: ${e.message}"
                try { meetingService.cancelRecording(meetingId) } catch (_: Exception) {}
                _currentMeetingId.value = null
            } finally {
                recorder.release()
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
                println("[Meeting] Finalizing meeting=$meetingId title=$title type=$meetingType duration=${duration}s")
                meetingService.finalizeRecording(
                    MeetingFinalizeDto(
                        meetingId = meetingId,
                        title = title,
                        meetingType = meetingType,
                        durationSeconds = duration,
                    ),
                )
                _currentMeetingId.value = null
                _recordingDuration.value = 0
                _readyToFinalize.value = false

                lastClientId?.let { loadMeetings(it, lastProjectId) }
                println("[Meeting] Finalization complete")
            } catch (e: Exception) {
                println("[Meeting] Error finalizing: ${e.message}")
                _error.value = "Chyba při dokončování nahrávky: ${e.message}"
            }
        }
    }

    fun cancelRecording() {
        val meetingId = _currentMeetingId.value ?: return

        durationUpdateJob?.cancel()
        _isRecording.value = false
        _recordingDuration.value = 0
        _readyToFinalize.value = false

        audioRecorder?.release()
        audioRecorder = null

        scope.launch {
            try {
                meetingService.cancelRecording(meetingId)
            } catch (_: Exception) {}
            _currentMeetingId.value = null
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun playAudio(meetingId: String) {
        if (_playingMeetingId.value == meetingId) {
            stopPlayback()
            return
        }
        stopPlayback()

        _playingMeetingId.value = meetingId
        scope.launch {
            try {
                val base64Data = meetingService.getAudioData(meetingId)
                val audioBytes = Base64.decode(base64Data)
                val player = AudioPlayer()
                audioPlayer = player
                player.play(audioBytes)

                // Monitor playback completion
                playbackMonitorJob?.cancel()
                playbackMonitorJob = scope.launch {
                    while (audioPlayer?.isPlaying == true) {
                        delay(500)
                    }
                    if (_playingMeetingId.value == meetingId) {
                        _playingMeetingId.value = null
                    }
                }
            } catch (e: Exception) {
                _error.value = "Chyba při přehrávání: ${e.message}"
                _playingMeetingId.value = null
            }
        }
    }

    fun stopPlayback() {
        playbackMonitorJob?.cancel()
        playbackMonitorJob = null
        audioPlayer?.stop()
        audioPlayer?.release()
        audioPlayer = null
        _playingMeetingId.value = null
    }

    fun selectMeeting(meeting: MeetingDto?) {
        if (meeting == null || meeting.id != _playingMeetingId.value) {
            stopPlayback()
        }
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
            } catch (_: Exception) {}
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

    fun loadDeletedMeetings(clientId: String, projectId: String? = null) {
        scope.launch {
            _isLoading.value = true
            try {
                _deletedMeetings.value = meetingService.listDeletedMeetings(clientId, projectId)
            } catch (e: Exception) {
                _error.value = "Nepodařilo se načíst koš: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun restoreMeeting(meetingId: String) {
        scope.launch {
            try {
                meetingService.restoreMeeting(meetingId)
                _deletedMeetings.value = _deletedMeetings.value.filter { it.id != meetingId }
                // Refresh active meetings to show restored item
                lastClientId?.let { loadMeetings(it, lastProjectId) }
            } catch (e: Exception) {
                _error.value = "Nepodařilo se obnovit schůzku: ${e.message}"
            }
        }
    }

    fun permanentlyDeleteMeeting(meetingId: String) {
        scope.launch {
            try {
                meetingService.permanentlyDeleteMeeting(meetingId)
                _deletedMeetings.value = _deletedMeetings.value.filter { it.id != meetingId }
            } catch (e: Exception) {
                _error.value = "Nepodařilo se trvale smazat schůzku: ${e.message}"
            }
        }
    }

    fun retranscribeMeeting(meetingId: String) {
        scope.launch {
            try {
                meetingService.retranscribeMeeting(meetingId)
                refreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba pri obnove prepisu: ${e.message}"
            }
        }
    }

    fun recorrectMeeting(meetingId: String) {
        scope.launch {
            try {
                meetingService.recorrectMeeting(meetingId)
                refreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba pri oprave prepisu: ${e.message}"
            }
        }
    }

    fun reindexMeeting(meetingId: String) {
        scope.launch {
            try {
                meetingService.reindexMeeting(meetingId)
                refreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba pri reindexaci: ${e.message}"
            }
        }
    }

    fun answerQuestions(meetingId: String, answers: List<CorrectionAnswerDto>) {
        scope.launch {
            try {
                meetingService.answerCorrectionQuestions(meetingId, answers)
                refreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba pri odesilani odpovedi: ${e.message}"
            }
        }
    }

    fun submitCorrectionFromSegment(
        clientId: String,
        projectId: String?,
        submit: TranscriptCorrectionSubmitDto,
    ) {
        scope.launch {
            try {
                correctionService?.submitCorrection(
                    submit.copy(clientId = clientId, projectId = projectId),
                )
            } catch (e: Exception) {
                _error.value = "Chyba pri ukladani korekce: ${e.message}"
            }
        }
    }

    fun applySegmentCorrection(meetingId: String, segmentIndex: Int, correctedText: String) {
        scope.launch {
            try {
                val updated = meetingService.applySegmentCorrection(meetingId, segmentIndex, correctedText)
                _selectedMeeting.value = updated
                _meetings.value = _meetings.value.map { if (it.id == meetingId) updated else it }
            } catch (e: Exception) {
                _error.value = "Chyba pri oprave segmentu: ${e.message}"
            }
        }
    }

    fun correctWithInstruction(meetingId: String, instruction: String) {
        scope.launch {
            _isCorrecting.value = true
            try {
                val updated = meetingService.correctWithInstruction(meetingId, instruction)
                _selectedMeeting.value = updated
                _meetings.value = _meetings.value.map { if (it.id == meetingId) updated else it }
            } catch (e: Exception) {
                _error.value = "Chyba pri instrukci korekce: ${e.message}"
            } finally {
                _isCorrecting.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Upload audio data in chunks of up to 4MB raw (≈5.3MB Base64).
     * This prevents WebSocket message size issues with large recordings.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun uploadInChunks(meetingId: String, data: ByteArray): Boolean {
        val chunkSizeBytes = 4 * 1024 * 1024 // 4MB per chunk
        val totalChunks = (data.size + chunkSizeBytes - 1) / chunkSizeBytes

        println("[Meeting] Uploading ${data.size} bytes in $totalChunks chunk(s)...")

        for (i in 0 until totalChunks) {
            val start = i * chunkSizeBytes
            val end = minOf(start + chunkSizeBytes, data.size)
            val chunk = data.copyOfRange(start, end)
            val isLast = i == totalChunks - 1

            try {
                val base64Data = Base64.encode(chunk)
                println("[Meeting] Uploading chunk ${i + 1}/$totalChunks (${chunk.size} bytes, ${base64Data.length} Base64 chars)...")
                meetingService.uploadAudioChunk(
                    AudioChunkDto(
                        meetingId = meetingId,
                        chunkIndex = chunkIndex++,
                        data = base64Data,
                        isLast = isLast,
                    ),
                )
                println("[Meeting] Chunk ${i + 1}/$totalChunks uploaded successfully")
            } catch (e: Exception) {
                println("[Meeting] Upload chunk ${i + 1}/$totalChunks FAILED: ${e::class.simpleName}: ${e.message}")
                e.printStackTrace()
                _error.value = "Chyba při odesílání zvuku: ${e.message}"
                return false
            }
        }

        println("[Meeting] All $totalChunks chunks uploaded successfully")
        return true
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
