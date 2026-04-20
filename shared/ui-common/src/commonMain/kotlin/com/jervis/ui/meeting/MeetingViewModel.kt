package com.jervis.ui.meeting

import com.jervis.dto.project.ProjectDto
import com.jervis.dto.project.filterVisible
import com.jervis.dto.meeting.AudioInputType
import com.jervis.dto.meeting.MeetingClassifyDto
import com.jervis.dto.meeting.CorrectionChatMessageDto
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.MeetingGroupDto
import com.jervis.dto.meeting.CorrectionAnswerDto
import com.jervis.dto.meeting.MeetingSummaryDto
import com.jervis.dto.meeting.MeetingTimelineDto
import com.jervis.dto.meeting.MeetingTypeEnum
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.dto.meeting.SpeakerCreateDto
import com.jervis.dto.meeting.SpeakerDto
import com.jervis.dto.meeting.SpeakerMappingDto
import com.jervis.dto.meeting.TranscriptCorrectionSubmitDto
import com.jervis.dto.meeting.VoiceSampleRefDto
import com.jervis.di.RpcConnectionManager
import com.jervis.di.postSseStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.jervis.dto.events.JervisEvent
import com.jervis.di.JervisRepository
import com.jervis.ui.audio.AudioPlayer
import com.jervis.ui.audio.AudioRecorder
import com.jervis.ui.audio.AudioRecordingConfig
import com.jervis.ui.audio.PlatformRecordingService
import com.jervis.ui.util.getDeviceName
import com.jervis.ui.audio.RecordingServiceBridge
import com.jervis.ui.storage.AudioChunkQueue
import com.jervis.ui.storage.RecordingSession
import com.jervis.ui.storage.RecordingSessionStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Upload state visible to the UI for progress/retry feedback. */
sealed class UploadState {
    data object Idle : UploadState()
    data object Uploading : UploadState()
    data class Retrying(val attempt: Int, val maxAttempts: Int) : UploadState()
    data object RetryFailed : UploadState()
}

class MeetingViewModel(
    private val connectionManager: RpcConnectionManager,
    internal val repository: JervisRepository,
    private val uploadService: RecordingUploadService,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + CoroutineExceptionHandler { _, e ->
        if (e !is CancellationException) {
            println("MeetingViewModel: uncaught exception: ${e::class.simpleName}: ${e.message}")
        }
    })

    private val _meetings = MutableStateFlow<List<MeetingDto>>(emptyList())
    val meetings: StateFlow<List<MeetingDto>> = _meetings.asStateFlow()

    private val _projects = MutableStateFlow<List<ProjectDto>>(emptyList())
    val projects: StateFlow<List<ProjectDto>> = _projects.asStateFlow()

    private val _projectGroups = MutableStateFlow<List<com.jervis.dto.project.ProjectGroupDto>>(emptyList())
    val projectGroups: StateFlow<List<com.jervis.dto.project.ProjectGroupDto>> = _projectGroups.asStateFlow()

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

    private val _liveHints = MutableStateFlow<List<String>>(emptyList())
    val liveHints: StateFlow<List<String>> = _liveHints.asStateFlow()

    private val _liveAssistActive = MutableStateFlow(false)
    val liveAssistActive: StateFlow<Boolean> = _liveAssistActive.asStateFlow()

    /** Index of the transcript segment currently being played, or -1 for full playback. */
    private val _playingSegmentIndex = MutableStateFlow(-1)
    val playingSegmentIndex: StateFlow<Int> = _playingSegmentIndex.asStateFlow()

    private val _playbackPositionSec = MutableStateFlow(0.0)
    val playbackPositionSec: StateFlow<Double> = _playbackPositionSec.asStateFlow()

    private val _playbackDurationSec = MutableStateFlow(0.0)
    val playbackDurationSec: StateFlow<Double> = _playbackDurationSec.asStateFlow()

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

    private val _unclassifiedMeetings = MutableStateFlow<List<MeetingDto>>(emptyList())
    val unclassifiedMeetings: StateFlow<List<MeetingDto>> = _unclassifiedMeetings.asStateFlow()

    private val _currentWeekMeetings = MutableStateFlow<List<MeetingSummaryDto>>(emptyList())
    val currentWeekMeetings: StateFlow<List<MeetingSummaryDto>> = _currentWeekMeetings.asStateFlow()

    private val _olderGroups = MutableStateFlow<List<MeetingGroupDto>>(emptyList())
    val olderGroups: StateFlow<List<MeetingGroupDto>> = _olderGroups.asStateFlow()

    private val _expandedGroups = MutableStateFlow<Map<String, List<MeetingSummaryDto>>>(emptyMap())
    val expandedGroups: StateFlow<Map<String, List<MeetingSummaryDto>>> = _expandedGroups.asStateFlow()

    private val _clientSpeakers = MutableStateFlow<List<SpeakerDto>>(emptyList())
    val clientSpeakers: StateFlow<List<SpeakerDto>> = _clientSpeakers.asStateFlow()

    private val _loadingGroups = MutableStateFlow<Set<String>>(emptySet())
    val loadingGroups: StateFlow<Set<String>> = _loadingGroups.asStateFlow()

    // Meeting Helper state
    private val _helperDevices = MutableStateFlow<List<com.jervis.dto.meeting.DeviceInfoDto>>(emptyList())
    val helperDevices: StateFlow<List<com.jervis.dto.meeting.DeviceInfoDto>> = _helperDevices.asStateFlow()

    private val _helperMessages = MutableStateFlow<List<com.jervis.dto.meeting.HelperMessageDto>>(emptyList())
    val helperMessages: StateFlow<List<com.jervis.dto.meeting.HelperMessageDto>> = _helperMessages.asStateFlow()

    private val _helperConnected = MutableStateFlow(false)
    val helperConnected: StateFlow<Boolean> = _helperConnected.asStateFlow()

    /** TTS toggle — when on, the device speaks each incoming SUGGESTION/ANSWER
     *  hint via the xTTS v2 GPU service (user's voice). Pure UI state here;
     *  audio playback wiring lands with the orchestrator streaming refactor. */
    private val _ttsEnabled = MutableStateFlow(false)
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    fun toggleTts() { _ttsEnabled.value = !_ttsEnabled.value }

    private var helperWsJob: Job? = null
    private var activeHelperDeviceId: String? = null

    private val platformRecordingService = PlatformRecordingService()
    private var audioRecorder: AudioRecorder? = null
    private var audioPlayer: AudioPlayer? = null
    private var durationUpdateJob: Job? = null
    private var chunkUploadJob: Job? = null
    private var playbackMonitorJob: Job? = null
    private var eventSubscriptionJob: Job? = null
    private var timelineSubscriptionJob: Job? = null
    private var chunkIndex = 0

    private var lastClientId: String? = null
    private var lastProjectId: String? = null
    private var pendingTitle: String? = null
    private var pendingMeetingType: MeetingTypeEnum? = null

    companion object {
        private const val CHUNK_UPLOAD_INTERVAL_MS = 10_000L // 10 seconds
    }

    init {
        // Auto-refresh meeting list when a recording session is finalized
        scope.launch {
            uploadService.sessionFinalized.collect { meetingId ->
                println("[MeetingVM] Session finalized (meeting=$meetingId), refreshing list")
                val cid = lastClientId
                if (cid != null) {
                    loadMeetings(cid, lastProjectId, silent = true)
                }
            }
        }

        // Listen for stop requests from platform controls (notification / lock screen)
        scope.launch {
            RecordingServiceBridge.stopRequested.collect {
                stopRecording()
            }
        }
    }

    /** Disconnect the active meeting helper session. */
    fun disconnectHelper() {
        val meetingId = _currentMeetingId.value ?: return
        scope.launch {
            try {
                repository.meetingHelper.stopHelper(meetingId)
            } catch (_: Exception) {}
        }
        activeHelperDeviceId = null
        _helperMessages.value = emptyList()
        _helperConnected.value = false
    }

    fun loadHelperDevices(clientId: String) {
        scope.launch {
            try {
                _helperDevices.value = repository.deviceTokens.listDevices(clientId)
            } catch (e: Exception) {
                println("[MeetingVM] Failed to load helper devices: ${e.message}")
            }
        }
    }

    fun loadMeetings(clientId: String?, projectId: String? = null, silent: Boolean = false) {
        lastClientId = clientId
        lastProjectId = projectId
        scope.launch {
            if (!silent) _isLoading.value = true
            try {
                _meetings.value = repository.meetings.listMeetings(clientId, projectId)
            } catch (e: Exception) {
                _error.value = "Nepodařilo se načíst schůzky: ${e.message}"
            } finally {
                if (!silent) _isLoading.value = false
            }
        }
    }

    /**
     * Push-only timeline subscription — server emits a fresh
     * [MeetingTimelineDto] on every mutation (finalize, transcription
     * done, classify, merge, delete, restore…). Replay=1 delivers the
     * current snapshot on subscribe, so the screen is never empty. See
     * `docs/guidelines.md` §9.
     */
    fun observeTimeline(clientId: String?, projectId: String? = null) {
        lastClientId = clientId
        lastProjectId = projectId
        timelineSubscriptionJob?.cancel()
        timelineSubscriptionJob = scope.launch {
            connectionManager.resilientFlow { services ->
                services.meetingService.subscribeTimeline(clientId, projectId)
            }.collect { timeline ->
                _currentWeekMeetings.value = timeline.currentWeek
                _olderGroups.value = timeline.olderGroups
                _isLoading.value = false
            }
        }
    }

    fun loadTimeline(clientId: String?, projectId: String? = null, silent: Boolean = false) {
        lastClientId = clientId
        lastProjectId = projectId
        scope.launch {
            if (!silent) {
                _isLoading.value = true
                _expandedGroups.value = emptyMap()
                _loadingGroups.value = emptySet()
            }
            try {
                val timeline = repository.meetings.getMeetingTimeline(clientId, projectId)
                _currentWeekMeetings.value = timeline.currentWeek
                _olderGroups.value = timeline.olderGroups
            } catch (e: Exception) {
                _error.value = "Nepodařilo se načíst přehled: ${e.message}"
            } finally {
                if (!silent) _isLoading.value = false
            }
        }
    }

    fun toggleGroup(group: MeetingGroupDto) {
        val key = group.periodStart
        if (_expandedGroups.value.containsKey(key)) {
            _expandedGroups.value = _expandedGroups.value - key
            return
        }
        // Global scope (lastClientId == null) expands cross-client groups.
        scope.launch {
            _loadingGroups.value = _loadingGroups.value + key
            try {
                val items = repository.meetings.listMeetingsByRange(
                    clientId = lastClientId,
                    projectId = lastProjectId,
                    fromIso = group.periodStart,
                    toIso = group.periodEnd,
                )
                _expandedGroups.value = _expandedGroups.value + (key to items)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                println("[Meeting] toggleGroup failed: ${e::class.simpleName}: ${e.message}")
                _error.value = "Nepodařilo se načíst skupinu: ${e.message}"
            } finally {
                _loadingGroups.value = _loadingGroups.value - key
            }
        }
    }

    fun loadProjects(clientId: String) {
        scope.launch {
            try {
                _projects.value = repository.projects.listProjectsForClient(clientId).filterVisible()
            } catch (_: Exception) {
                _projects.value = emptyList()
            }
        }
    }

    fun loadProjectGroups(clientId: String) {
        scope.launch {
            try {
                _projectGroups.value = repository.projectGroups.listGroupsForClient(clientId)
            } catch (_: Exception) {
                _projectGroups.value = emptyList()
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
                    is JervisEvent.MeetingHelperMessage -> handleHelperMessage(event)
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

        // Update timeline summary items
        _currentWeekMeetings.value = _currentWeekMeetings.value.map { summary ->
            if (summary.id == event.meetingId) {
                summary.copy(state = newState, errorMessage = event.errorMessage)
            } else {
                summary
            }
        }
        _expandedGroups.value = _expandedGroups.value.mapValues { (_, items) ->
            items.map { summary ->
                if (summary.id == event.meetingId) {
                    summary.copy(state = newState, errorMessage = event.errorMessage)
                } else {
                    summary
                }
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

    private fun handleHelperMessage(event: JervisEvent.MeetingHelperMessage) {
        // Only process messages for the currently active recording/helper session
        val currentId = _currentMeetingId.value ?: return
        if (event.meetingId != currentId) return

        // Mark as connected on first message
        if (!_helperConnected.value) {
            _helperConnected.value = true
        }

        // Convert event to HelperMessageDto and append
        val type = try {
            com.jervis.dto.meeting.HelperMessageType.valueOf(event.type.uppercase())
        } catch (_: Exception) {
            com.jervis.dto.meeting.HelperMessageType.STATUS
        }

        // Skip empty non-status messages
        if (event.text.isBlank() && type != com.jervis.dto.meeting.HelperMessageType.STATUS) return

        val dto = com.jervis.dto.meeting.HelperMessageDto(
            type = type,
            text = event.text,
            context = event.context,
            fromLang = event.fromLang,
            toLang = event.toLang,
            timestamp = event.timestamp,
        )
        _helperMessages.value = _helperMessages.value + dto
    }

    fun startQuickRecording() {
        startRecording(
            clientId = null,
            projectId = null,
            audioInputType = AudioInputType.MIXED,
            meetingType = MeetingTypeEnum.AD_HOC,
        )
    }

    private var liveAssistJob: Job? = null
    private var liveAssistSessionId: String? = null

    /**
     * Toggle live assist on/off during an active recording.
     * When turned on, starts SSE session for real-time transcription + KB hints.
     * When turned off, stops the live assist session.
     */
    fun toggleLiveAssist() {
        if (!_isRecording.value) return
        val localId = _currentMeetingId.value ?: return

        if (_liveAssistActive.value) {
            // Stop live assist
            liveAssistJob?.cancel()
            liveAssistJob = null
            val sid = liveAssistSessionId
            liveAssistSessionId = null
            _liveAssistActive.value = false
            _liveHints.value = emptyList()
            if (sid != null) {
                scope.launch {
                    try {
                        val serverUrl = connectionManager.baseUrl.trimEnd('/')
                        postSseStream(
                            url = "$serverUrl/api/v1/voice/session/stop?sessionId=$sid",
                            bodyBytes = """{}""".encodeToByteArray(),
                            contentType = "application/json",
                        ) { /* closing */ }
                    } catch (e: Exception) {
                        println("[Meeting] Live assist stop error: ${e.message}")
                    }
                }
            }
            println("[Meeting] Live assist disabled by user")
        } else {
            // Start live assist
            _liveAssistActive.value = true
            startLiveAssistSession(localId, lastClientId, lastProjectId)
            println("[Meeting] Live assist enabled by user")
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun startRecording(
        clientId: String? = null,
        projectId: String? = null,
        audioInputType: AudioInputType = AudioInputType.MIXED,
        recordingConfig: AudioRecordingConfig = AudioRecordingConfig(),
        title: String? = null,
        meetingType: MeetingTypeEnum? = null,
        liveAssist: Boolean = false,
        helperEnabled: Boolean = false,
    ) {
        if (clientId != null) lastClientId = clientId
        if (projectId != null) lastProjectId = projectId
        pendingTitle = title
        pendingMeetingType = meetingType
        scope.launch {
            val localId = "rec_${Uuid.random()}"

            val recorder = AudioRecorder()
            val started = recorder.startRecording(recordingConfig)
            if (!started) {
                println("[Meeting] AudioRecorder failed to start")
                _error.value = "Nepodařilo se spustit nahrávání zvuku"
                return@launch
            }

            audioRecorder = recorder
            _isRecording.value = true
            _currentMeetingId.value = localId
            chunkIndex = 0

            // Register session with upload service
            val session = RecordingSession(
                localId = localId,
                clientId = clientId,
                projectId = projectId,
                title = title,
                meetingType = meetingType?.name,
                audioInputType = audioInputType.name,
                startedAtMs = Clock.System.now().toEpochMilliseconds(),
                deviceName = getDeviceName(),
            )
            uploadService.registerSession(session)

            println("[Meeting] Recording started: localId=$localId, liveAssist=$liveAssist, helper=$helperEnabled")
            platformRecordingService.startBackgroundRecording(title ?: "Nahravani")
            startDurationUpdate()
            startChunkSaveJob(localId)

            // Start live assist dual pipeline — voice session for KB hints during recording
            if (liveAssist || helperEnabled) {
                startLiveAssistSession(localId, clientId, projectId, helperEnabled)
            }

            // Start meeting helper session on server — hints broadcast to every device
            if (helperEnabled) {
                activeHelperDeviceId = ""  // marker: helper was requested
                scope.launch {
                    try {
                        repository.meetingHelper.startHelper(
                            com.jervis.dto.meeting.HelperSessionStartDto(
                                meetingId = localId,
                                // deviceId intentionally blank — broadcast, not targeted.
                            )
                        )
                        println("[Meeting] Helper/companion session started (broadcast)")
                    } catch (e: Exception) {
                        println("[Meeting] Failed to start helper session: ${e.message}")
                    }
                }
            }
        }
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun startLiveAssistSession(meetingLocalId: String, clientId: String?, projectId: String?, helperEnabled: Boolean = false) {
        _liveAssistActive.value = true
        liveAssistJob = scope.launch {
            try {
                val serverUrl = connectionManager.baseUrl.trimEnd('/')
                val sessionBody = """{"source":"meeting_assist","tts":false,"liveAssist":true,"meetingId":"$meetingLocalId","wearableNotify":true,"helperEnabled":$helperEnabled}"""

                postSseStream(
                    url = "$serverUrl/api/v1/voice/session",
                    bodyBytes = sessionBody.encodeToByteArray(),
                    contentType = "application/json",
                ) { event ->
                    when (event.event) {
                        "session_started" -> {
                            val json = try { kotlinx.serialization.json.Json.parseToJsonElement(event.data).jsonObject } catch (_: Exception) { null }
                            liveAssistSessionId = json?.get("session_id")?.jsonPrimitive?.content
                            println("[Meeting] Live assist session started: $liveAssistSessionId")
                            // Chunks forwarded by startChunkSaveJob via forwardChunkToLiveAssist()
                        }
                        "hint" -> {
                            val json = try { kotlinx.serialization.json.Json.parseToJsonElement(event.data).jsonObject } catch (_: Exception) { null }
                            val hintText = json?.get("text")?.jsonPrimitive?.content
                            if (!hintText.isNullOrBlank()) {
                                _liveHints.value = _liveHints.value + hintText
                                println("[Meeting] Live hint: ${hintText.take(60)}")
                            }
                        }
                        "chunk_transcribed" -> {
                            // Partial transcript from live assist — can display in UI
                        }
                        "done" -> {
                            println("[Meeting] Live assist session ended")
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                println("[Meeting] Live assist error: ${e.message}")
            } finally {
                liveAssistSessionId = null
                _liveAssistActive.value = false
            }
        }
    }

    /** Called by chunk save job — sends a copy of the audio chunk to live assist session. */
    private fun forwardChunkToLiveAssist(chunkPcm: ByteArray, chunkIdx: Int) {
        val sid = liveAssistSessionId ?: return
        scope.launch {
            try {
                val serverUrl = connectionManager.baseUrl.trimEnd('/')
                val chunkWav = wrapChunkInWav(chunkPcm)
                val (ct, body) = com.jervis.di.buildMultipartBody(
                    fileFieldName = "file",
                    fileName = "assist_$chunkIdx.wav",
                    fileContentType = "audio/wav",
                    fileBytes = chunkWav,
                    fields = mapOf("chunk" to chunkIdx.toString()),
                )
                postSseStream(
                    url = "$serverUrl/api/v1/voice/session/chunk?sessionId=$sid",
                    bodyBytes = body,
                    contentType = ct,
                ) { /* events on session SSE */ }
            } catch (e: Exception) {
                println("[Meeting] Assist chunk $chunkIdx error: ${e.message}")
            }
        }
    }

    /** Simple WAV header for 16-bit mono 16kHz PCM. */
    private fun wrapChunkInWav(pcm: ByteArray, sampleRate: Int = 16000): ByteArray {
        val dataSize = pcm.size
        val byteRate = sampleRate * 2
        val header = ByteArray(44)
        fun writeInt(offset: Int, value: Int) { for (i in 0..3) header[offset + i] = (value shr (i * 8)).toByte() }
        fun writeShort(offset: Int, value: Int) { header[offset] = value.toByte(); header[offset + 1] = (value shr 8).toByte() }
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeInt(4, 36 + dataSize)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeInt(16, 16); writeShort(20, 1); writeShort(22, 1); writeInt(24, sampleRate); writeInt(28, byteRate); writeShort(32, 2); writeShort(34, 16)
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeInt(40, dataSize)
        return header + pcm
    }

    fun stopRecording() {
        if (!_isRecording.value) return
        _isRecording.value = false
        durationUpdateJob?.cancel()
        chunkUploadJob?.cancel()
        platformRecordingService.stopBackgroundRecording()

        // Stop live assist session
        liveAssistJob?.cancel()
        liveAssistJob = null
        val sid = liveAssistSessionId
        liveAssistSessionId = null
        if (sid != null) {
            scope.launch {
                try {
                    val serverUrl = connectionManager.baseUrl.trimEnd('/')
                    postSseStream(
                        url = "$serverUrl/api/v1/voice/session/stop?sessionId=$sid",
                        bodyBytes = """{}""".encodeToByteArray(),
                        contentType = "application/json",
                    ) { /* session closing events */ }
                } catch (e: Exception) {
                    println("[Meeting] Live assist stop error: ${e.message}")
                }
            }
        }
        _liveHints.value = emptyList()

        // Stop meeting helper session
        val helperMeetingId = _currentMeetingId.value
        if (activeHelperDeviceId != null && helperMeetingId != null) {
            scope.launch {
                try {
                    repository.meetingHelper.stopHelper(helperMeetingId)
                    println("[Meeting] Helper session stopped")
                } catch (e: Exception) {
                    println("[Meeting] Helper stop error: ${e.message}")
                }
            }
            activeHelperDeviceId = null
            _helperMessages.value = emptyList()
            _helperConnected.value = false
            helperWsJob?.cancel()
            helperWsJob = null
        }

        val recorder = audioRecorder ?: return
        audioRecorder = null
        val localId = _currentMeetingId.value ?: run {
            recorder.release()
            return
        }

        scope.launch {
            try {
                val tailData = recorder.stopRecording()
                if (tailData != null && tailData.isNotEmpty()) {
                    AudioChunkQueue.enqueue(localId, chunkIndex, tailData)
                    chunkIndex++
                }
                val duration = _recordingDuration.value
                uploadService.updateSession(localId) {
                    it.copy(
                        stoppedAtMs = Clock.System.now().toEpochMilliseconds(),
                        durationSeconds = duration,
                        chunkCount = chunkIndex,
                    )
                }
                _currentMeetingId.value = null
                _recordingDuration.value = 0
                println("[Meeting] Recording stopped: localId=$localId, $chunkIndex chunks saved")
            } finally {
                recorder.release()
            }
        }
    }

    fun cancelRecording() {
        val localId = _currentMeetingId.value ?: return
        durationUpdateJob?.cancel()
        chunkUploadJob?.cancel()
        _isRecording.value = false
        _recordingDuration.value = 0
        _uploadState.value = UploadState.Idle
        platformRecordingService.stopBackgroundRecording()

        audioRecorder?.release()
        audioRecorder = null

        val session = RecordingSessionStorage.load().find { it.localId == localId }
        uploadService.cancelSession(localId, session?.serverMeetingId)
        _currentMeetingId.value = null
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
        _playbackPositionSec.value = 0.0
        _playbackDurationSec.value = 0.0
        scope.launch {
            try {
                val base64Data = repository.meetings.getAudioData(meetingId)
                val audioBytes = Base64.decode(base64Data)
                val player = AudioPlayer()
                audioPlayer = player
                player.playAsync(audioBytes)

                // Update duration once available
                _playbackDurationSec.value = player.durationSec

                // Monitor position and completion
                playbackMonitorJob?.cancel()
                playbackMonitorJob = scope.launch {
                    while (audioPlayer?.isPlaying == true) {
                        _playbackPositionSec.value = audioPlayer?.positionSec ?: 0.0
                        delay(250)
                    }
                    _playbackPositionSec.value = 0.0
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

    fun seekPlayback(positionSec: Double) {
        audioPlayer?.seekTo(positionSec)
        _playbackPositionSec.value = positionSec
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
                    val base64Data = repository.meetings.getAudioData(meetingId)
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
        meeting?.clientId?.let { loadSpeakers(it) }
    }

    fun selectMeetingById(meetingId: String) {
        scope.launch {
            try {
                val meeting = repository.meetings.getMeeting(meetingId)
                _selectedMeeting.value = meeting
                meeting.clientId?.let { loadSpeakers(it) }
            } catch (e: Exception) {
                _error.value = "Nepodařilo se načíst detail: ${e.message}"
            }
        }
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
        val updated = repository.meetings.getMeeting(meetingId)
        _meetings.value = _meetings.value.map { if (it.id == meetingId) updated else it }
        if (_selectedMeeting.value?.id == meetingId) {
            _selectedMeeting.value = updated
        }
        // Also update summary in currentWeekMeetings (state/title may have changed)
        _currentWeekMeetings.value = _currentWeekMeetings.value.map { summary ->
            if (summary.id == meetingId) {
                summary.copy(
                    state = updated.state,
                    title = updated.title,
                    meetingType = updated.meetingType,
                )
            } else summary
        }
    }

    fun deleteMeeting(meetingId: String) {
        scope.launch {
            try {
                repository.meetings.deleteMeeting(meetingId)
                _meetings.value = _meetings.value.filter { it.id != meetingId }
                _currentWeekMeetings.value = _currentWeekMeetings.value.filter { it.id != meetingId }
                _expandedGroups.value = _expandedGroups.value.mapValues { (_, items) ->
                    items.filter { it.id != meetingId }
                }
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
                _deletedMeetings.value = repository.meetings.listDeletedMeetings(clientId, projectId)
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
                repository.meetings.restoreMeeting(meetingId)
                _deletedMeetings.value = _deletedMeetings.value.filter { it.id != meetingId }
                // Timeline refresh arrives automatically via subscribeTimeline push.
                // (subscribeTimeline push stream delivers the update)
            } catch (e: Exception) {
                _error.value = "Nepodařilo se obnovit schůzku: ${e.message}"
            }
        }
    }

    fun permanentlyDeleteMeeting(meetingId: String) {
        scope.launch {
            try {
                repository.meetings.permanentlyDeleteMeeting(meetingId)
                _deletedMeetings.value = _deletedMeetings.value.filter { it.id != meetingId }
            } catch (e: Exception) {
                _error.value = "Nepodařilo se trvale smazat schůzku: ${e.message}"
            }
        }
    }

    // ── Unclassified meetings ─────────────────────────────────────────

    fun loadUnclassifiedMeetings() {
        scope.launch {
            try {
                _unclassifiedMeetings.value = repository.meetings.listUnclassifiedMeetings()
            } catch (e: Exception) {
                _error.value = "Nepodařilo se načíst neklasifikované nahrávky: ${e.message}"
            }
        }
    }

    fun classifyMeeting(
        meetingId: String,
        clientId: String,
        projectId: String? = null,
        groupId: String? = null,
        title: String? = null,
        meetingType: MeetingTypeEnum? = null,
    ) {
        scope.launch {
            try {
                repository.meetings.classifyMeeting(
                    MeetingClassifyDto(
                        meetingId = meetingId,
                        clientId = clientId,
                        projectId = projectId,
                        groupId = groupId,
                        title = title,
                        meetingType = meetingType,
                    ),
                )
                // Remove from unclassified list and refresh both lists
                _unclassifiedMeetings.value = _unclassifiedMeetings.value.filter { it.id != meetingId }
                loadUnclassifiedMeetings()
                // (subscribeTimeline push stream delivers the update)
            } catch (e: Exception) {
                _error.value = "Nepodařilo se klasifikovat nahrávku: ${e.message}"
            }
        }
    }

    fun updateMeeting(
        meetingId: String,
        clientId: String,
        projectId: String? = null,
        groupId: String? = null,
        title: String? = null,
        meetingType: MeetingTypeEnum? = null,
    ) {
        scope.launch {
            try {
                val updated = repository.meetings.updateMeeting(
                    MeetingClassifyDto(
                        meetingId = meetingId,
                        clientId = clientId,
                        projectId = projectId,
                        groupId = groupId,
                        title = title,
                        meetingType = meetingType,
                    ),
                )
                _selectedMeeting.value = updated
                // Refresh both lists in case client/project changed
                loadUnclassifiedMeetings()
                // (subscribeTimeline push stream delivers the update)
            } catch (e: Exception) {
                _error.value = "Nepodařilo se aktualizovat meeting: ${e.message}"
            }
        }
    }

    // ── Transcription / Correction ──────────────────────────────────────

    fun stopTranscription(meetingId: String) {
        scope.launch {
            try {
                repository.meetings.stopTranscription(meetingId)
                doRefreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba při zastavení přepisu: ${e.message}"
            }
        }
    }

    fun retranscribeMeeting(meetingId: String) {
        scope.launch {
            try {
                repository.meetings.retranscribeMeeting(meetingId)
                doRefreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba pri obnove prepisu: ${e.message}"
            }
        }
    }

    fun dismissMeetingError(meetingId: String) {
        scope.launch {
            try {
                repository.meetings.dismissMeetingError(meetingId)
                doRefreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba při zamítnutí chyby: ${e.message}"
            }
        }
    }

    fun retranscribeSegment(meetingId: String, segmentIndex: Int) {
        scope.launch {
            try {
                repository.meetings.retranscribeSegments(meetingId, listOf(segmentIndex))
                doRefreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba při přepisu segmentu: ${e.message}"
            }
        }
    }

    fun recorrectMeeting(meetingId: String) {
        scope.launch {
            try {
                repository.meetings.recorrectMeeting(meetingId)
                doRefreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba pri oprave prepisu: ${e.message}"
            }
        }
    }

    fun reindexMeeting(meetingId: String) {
        scope.launch {
            try {
                repository.meetings.reindexMeeting(meetingId)
                doRefreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba pri reindexaci: ${e.message}"
            }
        }
    }

    fun answerQuestions(meetingId: String, answers: List<CorrectionAnswerDto>) {
        scope.launch {
            try {
                repository.meetings.answerCorrectionQuestions(meetingId, answers)
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
                repository.transcriptCorrections.submitCorrection(
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
                val updated = repository.meetings.applySegmentCorrection(meetingId, segmentIndex, correctedText)
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
                val updated = repository.meetings.correctWithInstruction(meetingId, instruction)
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

    // ── Chunk save internals ────────────────────────────────────────────

    /** Periodic job that drains recorder buffer and saves chunks to disk. */
    private fun startChunkSaveJob(localId: String) {
        chunkUploadJob = scope.launch {
            delay(CHUNK_UPLOAD_INTERVAL_MS)
            while (_isRecording.value) {
                val recorder = audioRecorder ?: break
                val chunk = recorder.getAndClearBuffer()
                if (chunk != null && chunk.isNotEmpty()) {
                    AudioChunkQueue.enqueue(localId, chunkIndex, chunk)
                    // Forward to live assist session (if active)
                    forwardChunkToLiveAssist(chunk, chunkIndex)
                    chunkIndex++
                    uploadService.updateSession(localId) { it.copy(chunkCount = chunkIndex) }
                }
                delay(CHUNK_UPLOAD_INTERVAL_MS)
            }
        }
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

    // ---- Speaker management ----

    fun loadSpeakers(clientId: String) {
        scope.launch {
            try {
                _clientSpeakers.value = repository.speakers.listSpeakers(clientId)
            } catch (_: Exception) {
                // Non-critical — speakers may not be available yet
            }
        }
    }

    fun assignSpeakers(meetingId: String, mapping: Map<String, String>) {
        scope.launch {
            try {
                repository.speakers.assignSpeakers(SpeakerMappingDto(meetingId, mapping))
                doRefreshMeeting(meetingId)
            } catch (e: Exception) {
                _error.value = "Chyba při přiřazení mluvčích: ${e.message}"
            }
        }
    }

    fun createSpeaker(request: SpeakerCreateDto) {
        scope.launch {
            try {
                repository.speakers.createSpeaker(request)
                loadSpeakers(request.clientIds.firstOrNull() ?: return@launch)
            } catch (e: Exception) {
                _error.value = "Chyba při vytváření řečníka: ${e.message}"
            }
        }
    }

    fun setVoiceSample(speakerId: String, voiceSample: VoiceSampleRefDto) {
        scope.launch {
            try {
                repository.speakers.setVoiceSample(speakerId, voiceSample)
                _selectedMeeting.value?.clientId?.let { loadSpeakers(it) }
            } catch (e: Exception) {
                _error.value = "Chyba při ukládání vzorku hlasu: ${e.message}"
            }
        }
    }

    fun setVoiceEmbedding(request: com.jervis.dto.meeting.SpeakerEmbeddingDto) {
        scope.launch {
            try {
                repository.speakers.setVoiceEmbedding(request)
                _selectedMeeting.value?.clientId?.let { loadSpeakers(it) }
            } catch (e: Exception) {
                _error.value = "Chyba při ukládání hlasového otisku: ${e.message}"
            }
        }
    }
}
