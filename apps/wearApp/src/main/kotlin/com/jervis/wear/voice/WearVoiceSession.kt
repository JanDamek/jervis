package com.jervis.wear.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket voice session for Wear OS.
 *
 * Direct connection to Jervis server (no phone relay).
 * Pipeline: mic PCM → WebSocket → server VAD + Whisper → intent → LLM → TTS audio.
 */
class WearVoiceSession private constructor() {

    companion object {
        val shared = WearVoiceSession()
        private const val SERVER_URL = "wss://jervis.damek-soft.eu/api/v1/voice/ws"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_INTERVAL_MS = 100L
    }

    // Observable state
    private val _state = MutableStateFlow(SessionState.IDLE)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _responseText = MutableStateFlow("")
    val responseText: StateFlow<String> = _responseText.asStateFlow()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    enum class SessionState {
        IDLE, CONNECTING, LISTENING, RECORDING, PROCESSING, PLAYING_TTS, ERROR
    }

    // Internal
    private val scope = CoroutineScope(Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var sendJob: Job? = null
    private val isTtsPlaying = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    fun start(context: Context) {
        if (_state.value != SessionState.IDLE) return

        // Check mic permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = SessionState.ERROR
            _statusText.value = "Mikrofon nepřístupný"
            return
        }

        _state.value = SessionState.CONNECTING
        _statusText.value = "Připojuji..."
        _transcript.value = ""
        _responseText.value = ""

        val request = Request.Builder().url(SERVER_URL).build()
        webSocket = client.newWebSocket(request, WsListener())
    }

    fun stop() {
        if (_state.value == SessionState.IDLE) return
        try {
            webSocket?.send("""{"type":"stop"}""")
        } catch (_: Exception) {}
        cleanup()
    }

    fun cancel() {
        cleanup()
    }

    // WebSocket listener
    private inner class WsListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected.set(true)

            // Send start message
            webSocket.send("""{"type":"start","source":"wear","client_id":"","project_id":"","tts":true}""")

            // Start mic recording
            startMicRecording()

            // Start sending audio chunks
            sendJob = scope.launch { sendLoop() }

            _state.value = SessionState.LISTENING
            _statusText.value = "Poslouchám..."
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleTextMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleBinaryMessage(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            println("[WearVoice] WebSocket failure: ${t.message}")
            isConnected.set(false)
            _statusText.value = "Chyba připojení"
            _state.value = SessionState.ERROR
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isConnected.set(false)
            if (_state.value != SessionState.IDLE) {
                cleanup()
            }
        }
    }

    private fun handleTextMessage(text: String) {
        val json = try { JSONObject(text) } catch (_: Exception) { return }
        val type = json.optString("type", "")
        val content = json.optString("text", null)

        when (type) {
            "listening" -> {
                _state.value = SessionState.LISTENING
                _statusText.value = "Poslouchám..."
            }
            "speech_start" -> {
                _state.value = SessionState.RECORDING
                _statusText.value = "Mluvíte..."
                _transcript.value = ""
                _responseText.value = ""
            }
            "transcribing" -> {
                if (content != null) {
                    _transcript.value = "${_transcript.value} $content".trim()
                    _statusText.value = "Přepisuji..."
                }
            }
            "transcribed" -> {
                if (content != null) _transcript.value = content
                _statusText.value = "Zpracovávám..."
                _state.value = SessionState.PROCESSING
            }
            "thinking" -> {
                // Proactive acknowledgment — Jervis confirms it heard and is working
                if (content != null) {
                    _statusText.value = content
                    _responseText.value = content
                }
                _state.value = SessionState.PROCESSING
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
                _statusText.value = "Uloženo"
            }
            "done" -> {
                _statusText.value = ""
                _state.value = SessionState.LISTENING
            }
            "error" -> {
                _statusText.value = content ?: "Chyba"
            }
        }
    }

    private fun handleBinaryMessage(data: ByteArray) {
        if (data.isEmpty()) return
        isTtsPlaying.set(true)
        _state.value = SessionState.PLAYING_TTS

        webSocket?.send("""{"type":"tts_playing"}""")

        scope.launch {
            try {
                playAudio(data)
            } catch (e: Exception) {
                println("[WearVoice] TTS playback error: ${e.message}")
            } finally {
                isTtsPlaying.set(false)
                _state.value = SessionState.LISTENING
                webSocket?.send("""{"type":"tts_finished"}""")
            }
        }
    }

    @Suppress("MissingPermission")
    private fun startMicRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2,
        )
        audioRecord?.startRecording()
    }

    private suspend fun sendLoop() {
        val chunkSize = (SAMPLE_RATE * 2 * CHUNK_INTERVAL_MS / 1000).toInt() // bytes per 100ms
        val buffer = ByteArray(chunkSize)

        while (isConnected.get() && _state.value != SessionState.IDLE) {
            if (isTtsPlaying.get()) {
                delay(CHUNK_INTERVAL_MS)
                continue
            }

            val read = audioRecord?.read(buffer, 0, chunkSize) ?: -1
            if (read > 0) {
                try {
                    webSocket?.send(buffer.copyOf(read).toByteString())
                } catch (_: Exception) {}
            }
        }
    }

    private fun playAudio(data: ByteArray) {
        val track = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(data.size)
            .build()

        audioTrack = track
        track.play()
        track.write(data, 0, data.size)
        // Wait for playback to finish
        val durationMs = (data.size.toLong() * 1000) / (SAMPLE_RATE * 2)
        Thread.sleep(durationMs + 100)
        track.stop()
        track.release()
        audioTrack = null
    }

    private fun cleanup() {
        sendJob?.cancel()
        sendJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        isTtsPlaying.set(false)
        isConnected.set(false)
        webSocket?.close(1000, "Client stop")
        webSocket = null
        _state.value = SessionState.IDLE
        _statusText.value = ""
    }
}
