package com.jervis.voice

import com.jervis.infrastructure.config.properties.TtsProperties
import com.jervis.infrastructure.config.properties.WhisperProperties
import com.jervis.meeting.WhisperRestClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mu.KotlinLogging
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * WebSocket handler for continuous voice sessions.
 *
 * Protocol:
 * - Client → Server Binary: PCM 16kHz mono 16-bit (100ms chunks, 3200 bytes)
 * - Client → Server Text: JSON control {"type":"start"|"tts_playing"|"tts_finished"|"stop", ...}
 * - Server → Client Text: JSON events {"type":"listening"|"transcribing"|"transcribed"|"token"|"response"|"stored"|"done", ...}
 * - Server → Client Binary: TTS audio (WAV)
 *
 * Pipeline per utterance:
 * 1. PCM chunks → SegmentAccumulator (5s) → Whisper GPU (parallel)
 * 2. EnergyVad detects SPEECH_END → flush accumulator → intent check
 * 3. Three-tier response:
 *    - Tier 1 (< 3s): KB + FREE LLM answer
 *    - Tier 2 (< 1s): "Zaznamenáno" (dictation → KB store)
 *    - Tier 3 (< 2s): "Odpovím na telefonu" (complex → orchestrator background)
 */
class VoiceWebSocketHandler(
    private val whisperRestClient: WhisperRestClient,
    private val whisperProperties: WhisperProperties,
    private val ttsProperties: TtsProperties,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val ttsClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 5_000
        }
    }

    private val orchestratorClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 60_000
        }
    }

    suspend fun handleSession(session: DefaultWebSocketSession) {
        var source = "unknown"
        var clientId = ""
        var projectId = ""
        var groupId = ""
        var ttsEnabled = true
        var started = false

        val vad = EnergyVad()
        val accumulator = SegmentAccumulator()
        val transcriptBuilder = StringBuilder()
        val ttsPlaying = AtomicBoolean(false)
        val scope = CoroutineScope(Dispatchers.IO)
        var whisperJob: Job? = null
        var idleTimeoutJob: Job? = null

        val orchestratorUrl = System.getenv("ORCHESTRATOR_URL") ?: "http://jervis-orchestrator:8090"

        fun resetIdleTimeout() {
            idleTimeoutJob?.cancel()
            idleTimeoutJob = scope.launch {
                delay(5 * 60 * 1000) // 5 minutes
                logger.info { "VOICE_WS: idle timeout, closing session" }
                session.sendJsonEvent("error", "Session timeout — 5 minut bez aktivity")
                session.close()
            }
        }

        try {
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        val msg = try { json.parseToJsonElement(text).jsonObject } catch (_: Exception) { continue }
                        val type = msg["type"]?.jsonPrimitive?.content ?: continue

                        when (type) {
                            "start" -> {
                                source = msg["source"]?.jsonPrimitive?.content ?: "unknown"
                                clientId = msg["client_id"]?.jsonPrimitive?.content ?: ""
                                projectId = msg["project_id"]?.jsonPrimitive?.content ?: ""
                                groupId = msg["group_id"]?.jsonPrimitive?.content ?: ""
                                ttsEnabled = msg["tts"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                                started = true
                                logger.info { "VOICE_WS: session started source=$source client=$clientId" }
                                session.sendJsonEvent("listening")
                                resetIdleTimeout()
                            }

                            "tts_playing" -> ttsPlaying.set(true)
                            "tts_finished" -> {
                                ttsPlaying.set(false)
                                vad.reset()
                                accumulator.reset()
                            }

                            "stop" -> {
                                logger.info { "VOICE_WS: client requested stop" }
                                // Flush any remaining audio
                                val remaining = accumulator.flush()
                                if (remaining != null && transcriptBuilder.isNotEmpty()) {
                                    processUtterance(
                                        session, transcriptBuilder.toString(), source,
                                        clientId, projectId, groupId, ttsEnabled,
                                        orchestratorUrl, scope,
                                    )
                                }
                                break
                            }
                        }
                    }

                    is Frame.Binary -> {
                        if (!started || ttsPlaying.get()) continue
                        resetIdleTimeout()

                        val pcmBytes = frame.readBytes()
                        val pcm16 = bytesToShorts(pcmBytes)
                        val chunkMs = (pcmBytes.size.toLong() * 1000) / (16000 * 2) // 16kHz, 16-bit

                        // VAD check
                        val vadEvent = vad.onChunk(pcm16, chunkMs)

                        when (vadEvent) {
                            EnergyVad.Event.SPEECH_START -> {
                                session.sendJsonEvent("speech_start")
                                accumulator.reset()
                                transcriptBuilder.clear()
                            }
                            else -> {}
                        }

                        // Accumulate audio → emit 5s segments for parallel Whisper
                        val segment = accumulator.addAudio(pcmBytes)
                        if (segment != null) {
                            // Transcribe 5s segment in background (parallel with recording)
                            whisperJob = scope.launch {
                                val text = transcribeSegment(segment)
                                if (text.isNotBlank()) {
                                    transcriptBuilder.append(text).append(" ")
                                    session.sendJsonEvent("transcribing", text)
                                }
                            }
                        }

                        // SPEECH_END → flush remaining audio, process complete utterance
                        if (vadEvent == EnergyVad.Event.SPEECH_END) {
                            // Wait for any in-flight Whisper job
                            whisperJob?.join()

                            // Flush remaining audio
                            val remaining = accumulator.flush()
                            if (remaining != null) {
                                val text = transcribeSegment(remaining)
                                if (text.isNotBlank()) {
                                    transcriptBuilder.append(text).append(" ")
                                }
                            }

                            val fullTranscript = transcriptBuilder.toString().trim()
                            if (fullTranscript.isNotBlank()) {
                                session.sendJsonEvent("transcribed", fullTranscript)

                                processUtterance(
                                    session, fullTranscript, source,
                                    clientId, projectId, groupId, ttsEnabled,
                                    orchestratorUrl, scope,
                                )
                            }

                            // Reset for next utterance
                            transcriptBuilder.clear()
                            vad.reset()
                            accumulator.reset()
                            session.sendJsonEvent("listening")
                        }
                    }

                    else -> {}
                }
            }
        } catch (e: Exception) {
            logger.warn { "VOICE_WS: session error: ${e.message}" }
        } finally {
            idleTimeoutJob?.cancel()
            whisperJob?.cancel()
            logger.info { "VOICE_WS: session closed source=$source" }
        }
    }

    /**
     * Transcribe a WAV audio segment via Whisper GPU.
     */
    private suspend fun transcribeSegment(wavBytes: ByteArray): String {
        val tempFile = Files.createTempFile("voice_ws_", ".wav")
        try {
            Files.write(tempFile, wavBytes)
            val opts = """{"model":"${whisperProperties.model}","beam_size":1,"vad_filter":true,"language":"cs"}"""
            val result = whisperRestClient.transcribe(
                baseUrl = whisperProperties.restRemoteUrl,
                audioFilePath = tempFile.toString(),
                optionsJson = opts,
            )
            return result.text.trim()
        } catch (e: Exception) {
            logger.warn { "VOICE_WS: Whisper failed: ${e.message}" }
            return ""
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    /**
     * Process a complete utterance: intent classify → 3-tier response.
     */
    private suspend fun processUtterance(
        session: DefaultWebSocketSession,
        transcript: String,
        source: String,
        clientId: String,
        projectId: String,
        groupId: String,
        ttsEnabled: Boolean,
        orchestratorUrl: String,
        scope: CoroutineScope,
    ) {
        session.sendJsonEvent("responding")

        val payload = buildJsonObject {
            put("text", transcript)
            put("source", source)
            put("client_id", clientId)
            put("project_id", projectId)
            put("group_id", groupId)
            put("tts", false) // We handle TTS ourselves (binary frames)
        }.toString()

        try {
            // Call Python voice pipeline with timeout — must respond within 3s
            val responseText = StringBuilder()
            var complete = false
            var stored = false

            withTimeoutOrNull(10_000) { // 10s max for pipeline
                val resp = orchestratorClient.post("$orchestratorUrl/voice/process") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }

                val channel = resp.bodyAsChannel()
                var currentEvent = ""
                var currentData = ""

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    when {
                        line.startsWith("event: ") -> currentEvent = line.removePrefix("event: ").trim()
                        line.startsWith("data: ") -> currentData = line.removePrefix("data: ").trim()
                        line.isBlank() && currentData.isNotEmpty() -> {
                            try {
                                val data = json.parseToJsonElement(currentData).jsonObject
                                val text = data["text"]?.jsonPrimitive?.content ?: ""
                                when (currentEvent) {
                                    "token" -> {
                                        responseText.append(text)
                                        session.sendJsonEvent("token", text)
                                    }
                                    "response" -> {
                                        if (responseText.isEmpty()) responseText.append(text)
                                        complete = true
                                        session.sendJsonEvent("response", text)
                                    }
                                    "stored" -> {
                                        stored = true
                                        val summary = data["summary"]?.jsonPrimitive?.content ?: transcript.take(80)
                                        session.sendJsonEvent("stored", summary)
                                        if (responseText.isEmpty()) responseText.append("Zaznamenáno.")
                                        complete = true
                                    }
                                    "error" -> {
                                        if (responseText.isEmpty()) responseText.append(text)
                                        complete = true
                                    }
                                    "done" -> complete = true
                                }
                            } catch (_: Exception) {}
                            currentEvent = ""
                            currentData = ""
                        }
                    }
                    if (complete) break
                }
            }

            // If pipeline didn't respond in time → tier 3 redirect
            if (responseText.isEmpty()) {
                responseText.append("Zpracovávám, odpovím na telefonu.")
                session.sendJsonEvent("response", responseText.toString())
                // Fire-and-forget: orchestrator continues in background
            }

            // TTS — send audio back as binary frame
            if (ttsEnabled && responseText.isNotBlank()) {
                try {
                    val ttsAudio = generateTts(responseText.toString().take(500))
                    if (ttsAudio != null && ttsAudio.isNotEmpty()) {
                        session.send(Frame.Binary(true, ttsAudio))
                    }
                } catch (e: Exception) {
                    logger.warn { "VOICE_WS: TTS failed: ${e.message}" }
                }
            }

            session.sendJsonEvent("done")

        } catch (e: Exception) {
            logger.warn { "VOICE_WS: utterance processing failed: ${e.message}" }
            session.sendJsonEvent("error", "Chyba zpracování: ${e.message?.take(100)}")
            session.sendJsonEvent("done")
        }
    }

    /**
     * Generate TTS audio via XTTS v2 on VD.
     */
    private suspend fun generateTts(text: String): ByteArray? {
        val resp = ttsClient.post("${ttsProperties.url}/tts") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"${text.replace("\"", "\\\"").replace("\n", " ")}","speed":${ttsProperties.speed}}""")
        }
        val audio = resp.readBytes()
        return if (audio.isNotEmpty()) audio else null
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }
}

/** Send a JSON text event to the WebSocket client. */
private suspend fun DefaultWebSocketSession.sendJsonEvent(type: String, text: String? = null) {
    val json = if (text != null) {
        """{"type":"$type","text":"${text.replace("\"", "\\\"").replace("\n", " ")}"}"""
    } else {
        """{"type":"$type"}"""
    }
    send(Frame.Text(json))
}
