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
 * Coordinates AudioRecorder (mic) + VoiceWebSocketClient (transport) + AudioPlayer (TTS).
 * Handles anti-echo (mute mic during TTS playback).
 *
 * Usage:
 * ```
 * val manager = VoiceSessionManager(scope)
 * manager.start(wsUrl, clientId, projectId)
 * // ... user speaks, server responds ...
 * manager.stop()
 * ```
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

    // ── Internal state ──────────────────────────────────────────────────

    private var sendJob: Job? = null
    private var receiveJob: Job? = null
    private var isTtsPlaying = false

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Start a continuous voice session.
     * Opens WebSocket, starts mic, begins sending PCM chunks.
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
                // Connect WebSocket
                wsClient.connect(wsUrl)
                _state.value = VoiceSessionState.LISTENING

                // Send start control message
                wsClient.sendText("""{"type":"start","source":"$source","client_id":"$clientId","project_id":"$projectId","group_id":"$groupId","tts":$tts}""")

                // Start mic recording
                val started = recorder.startRecording()
                if (!started) {
                    // Retry after permission dialog
                    delay(1500)
                    if (!recorder.startRecording()) {
                        _statusText.value = "Mikrofon nepřístupný"
                        _state.value = VoiceSessionState.ERROR
                        wsClient.close()
                        return@launch
                    }
                }

                // Start chunk sender (100ms intervals)
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

    /** Stop the voice session gracefully. */
    fun stop() {
        if (_state.value == VoiceSessionState.IDLE) return

        scope.launch {
            try {
                wsClient.sendText("""{"type":"stop"}""")
            } catch (_: Exception) {}
            cleanup()
        }
    }

    /** Cancel the session without processing remaining audio. */
    fun cancel() {
        scope.launch { cleanup() }
    }

    // ── Internal loops ──────────────────────────────────────────────────

    private suspend fun sendLoop() {
        while (wsClient.isConnected && _state.value != VoiceSessionState.IDLE) {
            delay(chunkIntervalMs)
            if (isTtsPlaying) continue // Anti-echo: don't send audio during TTS

            val chunk = recorder.getAndClearBuffer() ?: continue
            if (chunk.isEmpty()) continue
            wsClient.sendAudio(chunk)
        }
    }

    private suspend fun receiveLoop() {
        while (wsClient.isConnected) {
            val frame = wsClient.receive() ?: break
            val (isText, data) = frame

            if (isText) {
                handleTextEvent(data.decodeToString())
            } else {
                handleBinaryFrame(data)
            }
        }
        // WebSocket closed — cleanup if still active
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
                _statusText.value = "Poslouchám..."
            }
            "speech_start" -> {
                _state.value = VoiceSessionState.RECORDING
                _statusText.value = "Mluvíte..."
                _transcript.value = ""
                _responseText.value = ""
            }
            "transcribing" -> {
                if (content != null) {
                    _transcript.value = (_transcript.value + " " + content).trim()
                    _statusText.value = "Přepisuji..."
                }
            }
            "transcribed" -> {
                if (content != null) _transcript.value = content
                _statusText.value = "Zpracovávám..."
                _state.value = VoiceSessionState.PROCESSING
            }
            "responding" -> {
                _statusText.value = "Generuji odpověď..."
            }
            "token" -> {
                if (content != null) {
                    _responseText.value += content
                }
            }
            "response" -> {
                if (content != null) _responseText.value = content
            }
            "stored" -> {
                _statusText.value = "Uloženo do KB"
            }
            "done" -> {
                _statusText.value = ""
                _state.value = VoiceSessionState.LISTENING // Ready for next utterance
            }
            "error" -> {
                _statusText.value = content ?: "Chyba"
            }
        }
    }

    private fun handleBinaryFrame(data: ByteArray) {
        // TTS audio — play it and notify server
        if (data.isEmpty()) return
        isTtsPlaying = true
        _state.value = VoiceSessionState.PLAYING_TTS

        scope.launch {
            try {
                wsClient.sendText("""{"type":"tts_playing"}""")
                audioPlayer.play(data)
            } catch (e: Exception) {
                println("VoiceSession: TTS playback error: ${e.message}")
            } finally {
                isTtsPlaying = false
                _state.value = VoiceSessionState.LISTENING
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
    LISTENING,        // Mic active, VAD waiting for speech
    RECORDING,        // Speech detected, sending audio
    PROCESSING,       // Speech ended, server processing
    PLAYING_TTS,      // Server response playing as TTS
    ERROR,
}
