package com.jervis.ui.meeting

import com.jervis.dto.ProjectDto
import com.jervis.dto.meeting.AudioChunkDto
import com.jervis.dto.meeting.AudioInputType
import com.jervis.dto.meeting.MeetingCreateDto
import com.jervis.dto.meeting.CorrectionChatMessageDto
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.MeetingFinalizeDto
import com.jervis.dto.meeting.CorrectionAnswerDto
import com.jervis.dto.meeting.MeetingTypeEnum
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.dto.meeting.TranscriptCorrectionSubmitDto
import com.jervis.di.RpcConnectionManager
import com.jervis.dto.events.JervisEvent
import com.jervis.service.IMeetingService
import com.jervis.service.IProjectService
import com.jervis.ui.audio.AudioPlayer
import com.jervis.ui.audio.AudioRecorder
import com.jervis.ui.audio.AudioRecordingConfig
import com.jervis.ui.audio.PlatformRecordingService
import com.jervis.ui.audio.RecordingServiceBridge
import com.jervis.ui.storage.RecordingStateStorage
import com.jervis.ui.storage.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Clock
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

/** Upload state visible to the UI for progress/retry feedback. */
sealed class UploadState {
    data object Idle : UploadState()
    data object Uploading : UploadState()
    data class Retrying(val attempt: Int, val maxAttempts: Int) : UploadState()
    data object RetryFailed : UploadState()
}

class MeetingViewModel(
    private val connectionManager: RpcConnectionManager,
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

    /** Index of the transcript segment currently being played, or -1 for full playback. */
    private val _playingSegmentIndex = MutableStateFlow(-1)
    val playingSegmentIndex: StateFlow<Int> = _playingSegmentIndex.asStateFlow()

    /** Cached audio bytes for segment playback (avoids re-downloading per segment). */
    private var cachedAudioMeetingId: String? = null
    private var cachedAudioBytes: ByteArray? = null

    private val _isCorrecting = MutableStateFlow(false)
    val isCorrecting: StateFlow<Boolean> = _isCorrecting.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    private val _transcriptionProgress = MutableStateFlow<Map<String, Double>>(emptyMap())
    val transcriptionProgress: StateFlow<Map<String, Double>> = _transcriptionProgress.asStateFlow()

    private val _transcriptionLastSegment = MutableStateFlow<Map<String, String>>(emptyMap())
    val transcriptionLastSegment: StateFlow<Map<String, String>> = _transcriptionLastSegment.asStateFlow()

    // Correction progress: meetingId -> CorrectionProgressInfo
    data class CorrectionProgressInfo(val percent: Double, val chunksDone: Int, val totalChunks: Int, val message: String?, val tokensGenerated: Int = 0)
    private val _correctionProgress = MutableStateFlow<Map<String, CorrectionProgressInfo>>(emptyMap())
    val correctionProgress: StateFlow<Map<String, CorrectionProgressInfo>> = _correctionProgress.asStateFlow()

    private val _pendingChatMessage = MutableStateFlow<CorrectionChatMessageDto?>(null)
    val pendingChatMessage: StateFlow<CorrectionChatMessageDto?> = _pendingChatMessage.asStateFlow()

    private val _deletedMeetings = MutableStateFlow<List<MeetingDto>>(emptyList())
    val deletedMeetings: StateFlow<List<MeetingDto>> = _deletedMeetings.asStateFlow()

    private val platformRecordingService = PlatformRecordingService()
    private var audioRecorder: AudioRecorder? = null
    private var audioPlayer: AudioPlayer? = null
    private var durationUpdateJob: Job? = null
    private var chunkUploadJob: Job? = null
    private var playbackMonitorJob: Job? = null
    private var eventSubscriptionJob: Job? = null
    private var chunkIndex = 0

    private var lastClientId: String? = null
    private var lastProjectId: String? = null
    private var pendingTitle: String? = null
    private var pendingMeetingType: MeetingTypeEnum? = null

    companion object {
        private const val CHUNK_UPLOAD_INTERVAL_MS = 10_000L // 10 seconds
        private const val CHUNK_SIZE_BYTES = 4 * 1024 * 1024 // 4MB per RPC chunk
        private const val MAX_UPLOAD_RETRIES = 5
    }

    init {
        // Listen for stop requests from platform controls (notification / lock screen)
        scope.launch {
            RecordingServiceBridge.stopRequested.collect {
                stopRecording()
            }
        }
    }

    fun loadMeetings(clientId: String, projectId: String? = null, silent: Boolean = false) {
        lastClientId = clientId
        lastProjectId = projectId
        scope.launch {
            if (!silent) _isLoading.value = true
            try {
                _meetings.value = meetingService.listMeetings(clientId, projectId)
            } catch (e: Exception) {
                _error.value = "Nepodařilo se načíst schůzky: ${e.message}"
            } finally {
                if (!silent) _isLoading.value = false
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

    fun subscribeToEvents(clientId: String) {
        eventSubscriptionJob?.cancel()
        eventSubscriptionJob = scope.launch {
            connectionManager.resilientFlow { services ->
                services.notificationService.subscribeToEvents(clientId)
            }.collect { event ->
                when (event) {
                    is JervisEvent.MeetingStateChanged -> handleMeetingStateChanged(event)
                    is JervisEvent.MeetingTranscriptionProgress -> handleTranscriptionProgress(event)
                    is JervisEvent.MeetingCorrectionProgress -> handleCorrectionProgress(event)
                    else -> {}
                }
            }
        }
    }

    private fun handleMeetingStateChanged(event: JervisEvent.MeetingStateChanged) {
        val newState = try {
            MeetingStateEnum.valueOf(event.newState)
        } catch (_: Exception) { return }

        // Inline update for immediate visual feedback
        _meetings.value = _meetings.value.map { meeting ->
            if (meeting.id == event.meetingId) {
                meeting.copy(state = newState, errorMessage = event.errorMessage)
            } else {
                meeting
            }
        }

        if (_selectedMeeting.value?.id == event.meetingId) {
            _selectedMeeting.value = _selectedMeeting.value?.copy(
                state = newState, errorMessage = event.errorMessage,
            )
        }

        // Clear progress when leaving active processing states
        if (newState != MeetingStateEnum.TRANSCRIBING) {
            _transcriptionProgress.value = _transcriptionProgress.value - event.meetingId
            _transcriptionLastSegment.value = _transcriptionLastSegment.value - event.meetingId
        }
        if (newState != MeetingStateEnum.CORRECTING) {
            _correctionProgress.value = _correctionProgress.value - event.meetingId
        }

        // Full refresh to get transcript data etc.
        refreshMeeting(event.meetingId)
    }

    private fun handleTranscriptionProgress(event: JervisEvent.MeetingTranscriptionProgress) {
        _transcriptionProgress.value = _transcriptionProgress.value + (event.meetingId to event.percent)
        if (!event.lastSegmentText.isNullOrBlank()) {
            _transcriptionLastSegment.value = _transcriptionLastSegment.value + (event.meetingId to event.lastSegmentText!!)
        }
    }

    private fun handleCorrectionProgress(event: JervisEvent.MeetingCorrectionProgress) {
        _correctionProgress.value = _correctionProgress.value + (
            event.meetingId to CorrectionProgressInfo(
                percent = event.percent,
                chunksDone = event.chunksDone,
                totalChunks = event.totalChunks,
                message = event.message,
                tokensGenerated = event.tokensGenerated,
            )
        )
    }

    fun startRecording(
        clientId: String,
        projectId: String? = null,
        audioInputType: AudioInputType = AudioInputType.MIXED,
        recordingConfig: AudioRecordingConfig = AudioRecordingConfig(),
        title: String? = null,
        meetingType: MeetingTypeEnum? = null,
    ) {
        lastClientId = clientId
        lastProjectId = projectId
        pendingTitle = title
        pendingMeetingType = meetingType
        scope.launch {
            try {
                println("[Meeting] Starting recording for client=$clientId project=$projectId")
                val meeting = meetingService.startRecording(
                    MeetingCreateDto(
                        clientId = clientId,
                        projectId = projectId,
                        audioInputType = audioInputType,
                        title = title,
                        meetingType = meetingType,
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
                chunkIndex = 0

                // Persist recording state for crash recovery
                RecordingStateStorage.save(
                    RecordingState(
                        meetingId = meeting.id,
                        clientId = clientId,
                        projectId = projectId,
                        chunkIndex = 0,
                        title = title,
                        meetingType = meetingType?.name,
                        startedAtMs = Clock.System.now().toEpochMilliseconds(),
                    ),
                )

                println("[Meeting] Recording started successfully")
                platformRecordingService.startBackgroundRecording(title ?: "Nahravani")
                startDurationUpdate()
                startChunkUploadJob(meeting.id)
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
        chunkUploadJob?.cancel()
        platformRecordingService.stopBackgroundRecording()

        val recorder = audioRecorder ?: return
        audioRecorder = null // Clear immediately so second call returns at guard

        val meetingId = _currentMeetingId.value ?: run {
            recorder.release()
            return
        }

        scope.launch {
            println("[Meeting] Stopping recording for meeting=$meetingId")
            _isSaving.value = true
            _uploadState.value = UploadState.Uploading
            try {
                // Stop hardware and get the un-uploaded tail bytes
                val tailData = recorder.stopRecording()
                println("[Meeting] AudioRecorder returned ${tailData?.size ?: 0} tail bytes")

                if (tailData != null && tailData.isNotEmpty()) {
                    val uploaded = uploadChunkWithRetry(meetingId, tailData)
                    if (!uploaded) {
                        println("[Meeting] Tail upload failed — meeting preserved on server for retry")
                        // Do NOT cancel meeting — partial data is safe on server
                        _uploadState.value = UploadState.RetryFailed
                        return@launch
                    }
                }

                println("[Meeting] All chunks uploaded, auto-finalizing")
                finalizeRecording(pendingTitle, pendingMeetingType ?: MeetingTypeEnum.MEETING)
                _uploadState.value = UploadState.Idle
            } catch (e: Exception) {
                println("[Meeting] Error in stopRecording: ${e.message}")
                e.printStackTrace()
                _error.value = "Chyba při zastavení nahrávání: ${e.message}"
                // Do NOT cancel meeting — partial data is safe on server
                _uploadState.value = UploadState.RetryFailed
            } finally {
                _isSaving.value = false
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

                // Clear persisted recording state — successfully finalized
                RecordingStateStorage.save(null)

                lastClientId?.let { loadMeetings(it, lastProjectId, silent = true) }
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
        chunkUploadJob?.cancel()
        _isRecording.value = false
        _recordingDuration.value = 0
        _uploadState.value = UploadState.Idle
        platformRecordingService.stopBackgroundRecording()

        audioRecorder?.release()
        audioRecorder = null

        scope.launch {
            try {
                meetingService.cancelRecording(meetingId)
            } catch (_: Exception) {}
            _currentMeetingId.value = null
            RecordingStateStorage.save(null)
        }
    }

    // ── Interrupted recording recovery ──────────────────────────────────

    /** Check for a recording that was interrupted by crash/restart. */
    fun checkForInterruptedRecording(): RecordingState? {
        return RecordingStateStorage.load()
    }

    /** Resume an interrupted recording: verify on server and finalize (server has partial data). */
    fun resumeInterruptedUpload(state: RecordingState) {
        scope.launch {
            try {
                val uploadState = meetingService.getUploadState(state.meetingId)
                if (uploadState.state == MeetingStateEnum.RECORDING || uploadState.state == MeetingStateEnum.UPLOADING) {
                    _currentMeetingId.value = state.meetingId
                    chunkIndex = state.chunkIndex
                    pendingTitle = state.title
                    pendingMeetingType = state.meetingType?.let {
                        try { MeetingTypeEnum.valueOf(it) } catch (_: Exception) { null }
                    }
                    // Server has partial data from incremental uploads — finalize it
                    finalizeRecording(
                        state.title,
                        pendingMeetingType ?: MeetingTypeEnum.MEETING,
                    )
                } else {
                    // Meeting was already finalized/cancelled
                    RecordingStateStorage.save(null)
                }
            } catch (e: Exception) {
                _error.value = "Nepodařilo se obnovit nahrávku: ${e.message}"
            }
        }
    }

    /** Discard an interrupted recording. */
    fun discardInterruptedRecording(state: RecordingState) {
        scope.launch {
            try {
                meetingService.cancelRecording(state.meetingId)
            } catch (_: Exception) {}
            RecordingStateStorage.save(null)
        }
    }

    // ── Playback ────────────────────────────────────────────────────────

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
        _playingSegmentIndex.value = -1
    }

    /**
     * Play a single transcript segment (startSec to endSec).
     * If the same segment is already playing, toggles stop.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun playSegment(meetingId: String, segmentIndex: Int, startSec: Double, endSec: Double) {
        // Toggle off if same segment is playing
        if (_playingMeetingId.value == meetingId && _playingSegmentIndex.value == segmentIndex) {
            stopPlayback()
            return
        }
        stopPlayback()

        _playingMeetingId.value = meetingId
        _playingSegmentIndex.value = segmentIndex

        scope.launch {
            try {
                // Use cached audio if same meeting
                val audioBytes = if (cachedAudioMeetingId == meetingId && cachedAudioBytes != null) {
                    cachedAudioBytes!!
                } else {
                    val base64Data = meetingService.getAudioData(meetingId)
                    val bytes = Base64.decode(base64Data)
                    cachedAudioMeetingId = meetingId
                    cachedAudioBytes = bytes
                    bytes
                }

                val player = AudioPlayer()
                audioPlayer = player
                player.playRange(audioBytes, startSec, endSec)

                // Monitor playback completion
                playbackMonitorJob?.cancel()
                playbackMonitorJob = scope.launch {
                    while (audioPlayer?.isPlaying == true) {
                        delay(200)
                    }
                    if (_playingMeetingId.value == meetingId && _playingSegmentIndex.value == segmentIndex) {
                        _playingMeetingId.value = null
                        _playingSegmentIndex.value = -1
                    }
                }
            } catch (e: Exception) {
                _error.value = "Chyba pri prehravani segmentu: ${e.message}"
                _playingMeetingId.value = null
                _playingSegmentIndex.value = -1
            }
        }
    }

    // ── Meeting CRUD ────────────────────────────────────────────────────

    fun selectMeeting(meeting: MeetingDto?) {
        if (meeting == null || meeting.id != _playingMeetingId.value) {
            stopPlayback()
        }
        if (meeting?.id != cachedAudioMeetingId) {
            cachedAudioMeetingId = null
            cachedAudioBytes = null
        }
        _selectedMeeting.value = meeting
    }

    fun refreshMeeting(meetingId: String) {
        scope.launch {
            try {
                doRefreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba při obnovení schůzky: ${e.message}"
            }
        }
    }

    private suspend fun doRefreshMeeting(meetingId: String) {
        val updated = meetingService.getMeeting(meetingId)
        _meetings.value = _meetings.value.map { if (it.id == meetingId) updated else it }
        if (_selectedMeeting.value?.id == meetingId) {
            _selectedMeeting.value = updated
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

    // ── Transcription / Correction ──────────────────────────────────────

    fun stopTranscription(meetingId: String) {
        scope.launch {
            try {
                meetingService.stopTranscription(meetingId)
                doRefreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba při zastavení přepisu: ${e.message}"
            }
        }
    }

    fun retranscribeMeeting(meetingId: String) {
        scope.launch {
            try {
                meetingService.retranscribeMeeting(meetingId)
                doRefreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba pri obnove prepisu: ${e.message}"
            }
        }
    }

    fun dismissMeetingError(meetingId: String) {
        scope.launch {
            try {
                meetingService.dismissMeetingError(meetingId)
                doRefreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba při zamítnutí chyby: ${e.message}"
            }
        }
    }

    fun retranscribeSegment(meetingId: String, segmentIndex: Int) {
        scope.launch {
            try {
                meetingService.retranscribeSegments(meetingId, listOf(segmentIndex))
                doRefreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba při přepisu segmentu: ${e.message}"
            }
        }
    }

    fun recorrectMeeting(meetingId: String) {
        scope.launch {
            try {
                meetingService.recorrectMeeting(meetingId)
                doRefreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba pri oprave prepisu: ${e.message}"
            }
        }
    }

    fun reindexMeeting(meetingId: String) {
        scope.launch {
            try {
                meetingService.reindexMeeting(meetingId)
                doRefreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba pri reindexaci: ${e.message}"
            }
        }
    }

    fun answerQuestions(meetingId: String, answers: List<CorrectionAnswerDto>) {
        scope.launch {
            try {
                meetingService.answerCorrectionQuestions(meetingId, answers)
                doRefreshMeeting(meetingId)
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
            // Optimistic user message
            val userMsg = CorrectionChatMessageDto(
                role = com.jervis.dto.meeting.CorrectionChatRole.USER,
                text = instruction,
                timestamp = kotlinx.datetime.Instant.fromEpochMilliseconds(
                    kotlin.time.Clock.System.now().toEpochMilliseconds()
                ).toString(),
            )
            _pendingChatMessage.value = userMsg
            try {
                val updated = meetingService.correctWithInstruction(meetingId, instruction)
                _selectedMeeting.value = updated
                _meetings.value = _meetings.value.map { if (it.id == meetingId) updated else it }
            } catch (e: Exception) {
                _error.value = "Chyba pri instrukci korekce: ${e.message}"
            } finally {
                _pendingChatMessage.value = null
                _isCorrecting.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    // ── Chunked upload internals ────────────────────────────────────────

    /** Periodic job that drains the recorder buffer and uploads chunks during recording. */
    private fun startChunkUploadJob(meetingId: String) {
        chunkUploadJob = scope.launch {
            delay(CHUNK_UPLOAD_INTERVAL_MS) // initial accumulation delay
            while (_isRecording.value) {
                val recorder = audioRecorder ?: break
                val chunk = recorder.getAndClearBuffer()
                if (chunk != null && chunk.isNotEmpty()) {
                    println("[Meeting] Periodic upload: ${chunk.size} bytes for meeting=$meetingId")
                    _uploadState.value = UploadState.Uploading
                    val ok = uploadChunkWithRetry(meetingId, chunk)
                    if (!ok) {
                        println("[Meeting] Periodic chunk upload failed — will retry next cycle")
                        // Don't break the loop — keep recording, try again next interval
                    } else {
                        _uploadState.value = UploadState.Idle
                    }
                }
                delay(CHUNK_UPLOAD_INTERVAL_MS)
            }
        }
    }

    /**
     * Upload raw audio data in 4MB sub-chunks with exponential backoff retry.
     * Returns true if all sub-chunks were uploaded successfully.
     * On failure, does NOT cancel the meeting — partial data is preserved on the server.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun uploadChunkWithRetry(meetingId: String, rawData: ByteArray): Boolean {
        var offset = 0
        while (offset < rawData.size) {
            val end = minOf(offset + CHUNK_SIZE_BYTES, rawData.size)
            val subChunk = rawData.copyOfRange(offset, end)
            val base64Data = Base64.encode(subChunk)

            var retryCount = 0
            var success = false
            while (retryCount < MAX_UPLOAD_RETRIES && !success) {
                try {
                    meetingService.uploadAudioChunk(
                        AudioChunkDto(
                            meetingId = meetingId,
                            chunkIndex = chunkIndex,
                            data = base64Data,
                        ),
                    )
                    success = true
                    chunkIndex++

                    // Persist updated chunk index for crash recovery
                    RecordingStateStorage.load()?.let { state ->
                        RecordingStateStorage.save(state.copy(chunkIndex = chunkIndex))
                    }

                    println("[Meeting] Chunk $chunkIndex uploaded (${subChunk.size} bytes)")
                } catch (e: Exception) {
                    retryCount++
                    if (retryCount >= MAX_UPLOAD_RETRIES) {
                        println("[Meeting] Chunk upload FAILED after $MAX_UPLOAD_RETRIES retries: ${e.message}")
                        _error.value = "Odesílání selhalo po $MAX_UPLOAD_RETRIES pokusech: ${e.message}"
                        _uploadState.value = UploadState.RetryFailed
                        return false
                    }
                    val delayMs = minOf(1000L * (1L shl retryCount), 30_000L)
                    println("[Meeting] Chunk upload failed, retry $retryCount/$MAX_UPLOAD_RETRIES in ${delayMs}ms: ${e.message}")
                    _uploadState.value = UploadState.Retrying(retryCount, MAX_UPLOAD_RETRIES)
                    delay(delayMs)
                }
            }
            offset = end
        }
        return true
    }

    private fun startDurationUpdate() {
        durationUpdateJob = scope.launch {
            while (_isRecording.value) {
                val dur = audioRecorder?.durationSeconds ?: 0
                _recordingDuration.value = dur
                platformRecordingService.updateDuration(dur)
                delay(1000)
            }
        }
    }
}
