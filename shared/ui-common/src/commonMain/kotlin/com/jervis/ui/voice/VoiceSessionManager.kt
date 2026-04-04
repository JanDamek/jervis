package com.jervis.ui.voice

import com.jervis.ui.audio.AudioPlayer
import com.jervis.ui.audio.AudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Manages a continuous voice session over WebSocket.
 *
 * DESIGN: Mic NEVER stops. Audio is always captured and sent to server.
 * Server handles VAD, transcription, and response generation.
 * TTS is interruptible — if user speaks during TTS, it stops.
 *
 * Coordinates AudioRecorder (mic) + VoiceWebSocketClient (transport) + AudioPlayer (TTS).
 */
class VoiceSessionManager(
    private val scope: CoroutineScope,
) {
    private val recorder = AudioRecorder()
    private val wsClient = VoiceWebSocketClient()
    private val audioPlayer = AudioPlayer()
    private val json = Json { ignoreUnknownKeys = true }

    // Audio chunk interval: 100ms = 3200 bytes at 16kHz/16-bit/mono
    private val chunkIntervalMs = 100L

    // ── Observable state ────────────────────────────────────────────────

    private val _state = MutableStateFlow(VoiceSessionState.IDLE)
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _responseText = MutableStateFlow("")
    val responseText: StateFlow<String> = _responseText.asStateFlow()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    /** Real-time audio amplitude (0.0–1.0) from mic PCM data. */
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    // ── Internal state ──────────────────────────────────────────────────

    private var sendJob: Job? = null
    private var receiveJob: Job? = null

    // TTS is interruptible — track playing state for server notification only
    @kotlin.concurrent.Volatile
    private var isTtsPlaying = false

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Start a continuous voice session.
     * Opens WebSocket, starts mic, begins sending PCM chunks.
     * Mic NEVER stops until session is explicitly stopped.
     */
    fun start(
        wsUrl: String,
        source: String = "app",
        clientId: String = "",
        projectId: String = "",
        groupId: String = "",
        tts: Boolean = true,
    ) {
        if (_state.value != VoiceSessionState.IDLE) return

        _state.value = VoiceSessionState.CONNECTING
        _transcript.value = ""
        _responseText.value = ""
        _statusText.value = "Připojuji..."

        scope.launch {
            try {
                wsClient.connect(wsUrl)

                if (!wsClient.isConnected) {
                    _statusText.value = "Připojení selhalo"
                    _state.value = VoiceSessionState.ERROR
                    return@launch
                }

                _state.value = VoiceSessionState.LISTENING

                // Send start control message
                wsClient.sendText("""{"type":"start","source":"$source","client_id":"$clientId","project_id":"$projectId","group_id":"$groupId","tts":$tts}""")

                // Start mic recording
                val started = recorder.startRecording()
                if (!started) {
                    delay(1500)
                    if (!recorder.startRecording()) {
                        _statusText.value = "Mikrofon nepřístupný"
                        _state.value = VoiceSessionState.ERROR
                        wsClient.close()
                        return@launch
                    }
                }

                // Start chunk sender — NEVER stops, always sends audio
                sendJob = scope.launch { sendLoop() }

                // Start event receiver
                receiveJob = scope.launch { receiveLoop() }

            } catch (e: Exception) {
                println("VoiceSession: start error: ${e.message}")
                _statusText.value = "Chyba připojení"
                _state.value = VoiceSessionState.ERROR
            }
        }
    }

    fun stop() {
        if (_state.value == VoiceSessionState.IDLE) return

        scope.launch {
            try {
                wsClient.sendText("""{"type":"stop"}""")
            } catch (_: Exception) {}
            cleanup()
        }
    }

    fun cancel() {
        scope.launch { cleanup() }
    }

    // ── Internal loops ──────────────────────────────────────────────────

    private var sendCounter = 0

    /**
     * Continuously sends mic audio to server.
     *
     * NEVER stops — audio is always captured and sent.
     * Server handles all the logic (VAD, TTS interruption, etc.).
     */
    private suspend fun sendLoop() {
        println("VoiceSession: sendLoop started")
        while (wsClient.isConnected && _state.value != VoiceSessionState.IDLE) {
            delay(chunkIntervalMs)

            val chunk = recorder.getAndClearBuffer() ?: continue
            if (chunk.isEmpty()) continue

            // Calculate real-time audio level (RMS) from PCM 16-bit samples
            val rms = calculateRms(chunk)
            _audioLevel.value = rms

            // ALWAYS send audio — even during TTS playback
            // Server needs audio to detect user interruption
            wsClient.sendAudio(chunk)

            sendCounter++
            if (sendCounter % 50 == 0) { // Every ~5s
                println("VoiceSession: sent $sendCounter chunks, rms=$rms tts=$isTtsPlaying")
            }
        }
        println("VoiceSession: sendLoop ended")
    }

    /** Calculate RMS audio level from PCM 16-bit LE mono bytes, normalized to 0.0–1.0. */
    private fun calculateRms(pcmBytes: ByteArray): Float {
        if (pcmBytes.size < 2) return 0f
        var sum = 0.0
        val numSamples = pcmBytes.size / 2
        for (i in 0 until numSamples) {
            val low = pcmBytes[i * 2].toInt() and 0xFF
            val high = pcmBytes[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            val normalized = sample.toDouble() / 32768.0
            sum += normalized * normalized
        }
        return kotlin.math.sqrt(sum / numSamples).toFloat().coerceIn(0f, 1f)
    }

    private suspend fun receiveLoop() {
        println("VoiceSession: receiveLoop started")
        while (wsClient.isConnected) {
            val frame = wsClient.receive() ?: break
            val (isText, data) = frame

            if (isText) {
                handleTextEvent(data.decodeToString())
            } else {
                handleBinaryFrame(data)
            }
        }
        if (_state.value != VoiceSessionState.IDLE) {
            cleanup()
        }
    }

    private fun handleTextEvent(text: String) {
        val obj = try { json.parseToJsonElement(text).jsonObject } catch (_: Exception) { return }
        val type = obj["type"]?.jsonPrimitive?.content ?: return
        val content = obj["text"]?.jsonPrimitive?.content

        when (type) {
            "listening" -> {
                _state.value = VoiceSessionState.LISTENING
            }
            "speech_start" -> {
                _state.value = VoiceSessionState.RECORDING
                _statusText.value = ""
                // New utterance — clear response for new assistant answer
                _responseText.value = ""
            }
            "transcribing" -> {
                // Append partial transcription — accumulates across segments
                if (content != null) {
                    _transcript.value = (_transcript.value + " " + content).trim()
                }
            }
            "transcribed" -> {
                // Final transcript for this utterance
                if (content != null) _transcript.value = content
                _state.value = VoiceSessionState.PROCESSING
            }
            "thinking" -> {
                // Proactive acknowledgment — show as initial response
                if (content != null) {
                    _responseText.value = content
                }
                _state.value = VoiceSessionState.PROCESSING
            }
            "responding" -> { /* keep current state */ }
            "token" -> {
                // Stream response tokens — append to response text
                if (content != null) {
                    _responseText.value += content
                }
            }
            "response" -> {
                // Full response — replace everything
                if (content != null) _responseText.value = content
            }
            "stored" -> {
                if (content != null) _responseText.value = "Uloženo: $content"
            }
            "tts_start" -> {
                _state.value = VoiceSessionState.PLAYING_TTS
            }
            "tts_stop" -> {
                audioPlayer.stop()
                isTtsPlaying = false
                _state.value = VoiceSessionState.LISTENING
            }
            "done" -> {
                _statusText.value = ""
                if (!isTtsPlaying) {
                    _state.value = VoiceSessionState.LISTENING
                }
            }
            "error" -> {
                _statusText.value = content ?: "Chyba"
            }
        }
    }

    private fun handleBinaryFrame(data: ByteArray) {
        if (data.isEmpty()) return

        isTtsPlaying = true
        _state.value = VoiceSessionState.PLAYING_TTS

        scope.launch {
            try {
                wsClient.sendText("""{"type":"tts_playing"}""")
                // Play TTS — blocking, but mic keeps sending audio in sendLoop
                audioPlayer.play(data)
            } catch (e: Exception) {
                println("VoiceSession: TTS playback error: ${e.message}")
            } finally {
                isTtsPlaying = false
                _state.value = VoiceSessionState.LISTENING
                _transcript.value = ""
                _responseText.value = ""
                try {
                    wsClient.sendText("""{"type":"tts_finished"}""")
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun cleanup() {
        sendJob?.cancel()
        receiveJob?.cancel()
        sendJob = null
        receiveJob = null
        recorder.stopRecording()
        audioPlayer.stop()
        try { wsClient.close() } catch (_: Exception) {}
        isTtsPlaying = false
        _state.value = VoiceSessionState.IDLE
        _statusText.value = ""
    }
}

/** Observable state of a voice session. */
enum class VoiceSessionState {
    IDLE,
    CONNECTING,
    LISTENING,        // Mic active, no speech detected (but always recording!)
    RECORDING,        // Speech detected, sending audio (always sending anyway)
    PROCESSING,       // Server processing utterance (mic still active!)
    PLAYING_TTS,      // TTS playing (mic still active! Can be interrupted)
    ERROR,
}
