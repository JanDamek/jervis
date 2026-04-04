package com.jervis.voice

import com.jervis.infrastructure.config.properties.TtsProperties
import com.jervis.infrastructure.config.properties.WhisperProperties
import com.jervis.meeting.WhisperRestClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
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
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * WebSocket handler for continuous voice sessions.
 *
 * DESIGN PRINCIPLES:
 * - Mic NEVER stops — always accepting and processing audio
 * - All processing in background coroutines — frame loop never blocks
 * - Semantic boundary detection — understands from content when to respond, not from silence
 * - TTS is interruptible — if user speaks during TTS, it stops
 * - Parallel Whisper transcription — 5s segments transcribed while user speaks
 *
 * Protocol:
 * - Client → Server Binary: PCM 16kHz mono 16-bit (100ms chunks, 3200 bytes)
 * - Client → Server Text: JSON control messages
 * - Server → Client Text: JSON events
 * - Server → Client Binary: TTS audio (WAV)
 */
class VoiceWebSocketHandler(
    private val whisperRestClient: WhisperRestClient,
    private val whisperProperties: WhisperProperties,
    private val ttsProperties: TtsProperties,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Known Whisper v3 hallucination phrases produced on silence/noise. */
    private val whisperHallucinations = setOf(
        "titulky vytvořil johnnyx",
        "titulky vytvořil johnyx",
        "titulky vytvoril johnnyx",
        "titulky vytvoril johnyx",
        "titulky vytvořil",
        "subtitles by",
        "thank you for watching",
        "děkuji za pozornost",
        "děkuji za sledování",
        "napište do komentářů",
        "překlad:",
        "www.",
        "amara.org",
    )

    private fun isWhisperHallucination(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.length < 3) return true
        return whisperHallucinations.any { normalized.contains(it) }
    }

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

        val vad = EnergyVad(
            silenceTimeoutMs = 1500, // 1.5s — give user time to think
        )
        val accumulator = SegmentAccumulator()
        val transcriptBuilder = StringBuilder()
        val transcriptLock = Any()
        val scope = CoroutineScope(Dispatchers.IO)

        // Track active background jobs — never block main loop
        var whisperJob: Job? = null
        var processingJob: Job? = null
        var ttsJob: Job? = null
        var idleTimeoutJob: Job? = null

        val ttsPlaying = AtomicBoolean(false)

        // Track whether we've already started processing current utterance
        val utteranceProcessed = AtomicBoolean(false)

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

        fun getAndClearTranscript(): String {
            synchronized(transcriptLock) {
                val text = transcriptBuilder.toString().trim()
                transcriptBuilder.clear()
                return text
            }
        }

        fun appendTranscript(text: String) {
            synchronized(transcriptLock) {
                transcriptBuilder.append(text).append(" ")
            }
        }

        fun currentTranscript(): String {
            synchronized(transcriptLock) {
                return transcriptBuilder.toString().trim()
            }
        }

        /**
         * Check if accumulated transcript is a complete thought worth processing.
         *
         * Uses content-based heuristics — understands from the text when to respond,
         * not just from silence. User can pause to think without triggering processing.
         *
         * Called after VAD detects extended silence AND we have accumulated text.
         */
        fun isCompleteThought(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.length < 5) return false

            // Direct question — always complete
            if (trimmed.endsWith("?")) return true

            // Sentence-ending punctuation with minimum length
            if (trimmed.length > 15 && (trimmed.endsWith(".") || trimmed.endsWith("!"))) return true

            // Command verbs at the start — likely complete
            val lower = trimmed.lowercase()
            val commandStarts = listOf(
                "udělej", "nastav", "spusť", "vytvoř", "napiš", "pošli", "zapiš",
                "najdi", "zobraz", "otevři", "zavři", "restartuj", "smaž",
                "řekni", "odpověz", "vysvětli", "popiš", "shrň",
            )
            if (commandStarts.any { lower.startsWith(it) } && trimmed.length > 10) return true

            // If we have enough text (>30 chars) and silence is long, consider it complete
            if (trimmed.length > 30) return true

            return false
        }

        /**
         * Process an utterance in background — NEVER blocks the frame loop.
         * Handles response generation, TTS, and state transitions.
         */
        fun launchUtteranceProcessing(transcript: String) {
            if (transcript.isBlank()) return

            // Cancel any previous processing + TTS
            processingJob?.cancel()
            if (ttsPlaying.get()) {
                ttsJob?.cancel()
                ttsPlaying.set(false)
                scope.launch { session.sendJsonEvent("tts_stop") }
            }

            utteranceProcessed.set(true)

            processingJob = scope.launch {
                try {
                    session.sendJsonEvent("transcribed", transcript)

                    // Proactive acknowledgment
                    session.sendJsonEvent("thinking", pickAcknowledgment(transcript))

                    // Process utterance
                    session.sendJsonEvent("responding")
                    val responseText = processUtterance(
                        session, transcript, source,
                        clientId, projectId, groupId, ttsEnabled,
                        orchestratorUrl,
                    )

                    // Generate and play TTS in background (non-blocking)
                    // TTS generation is NOT cancellable — always let HTTP request finish
                    if (ttsEnabled && responseText.isNotBlank()) {
                        ttsJob = scope.launch {
                            try {
                                val ttsAudio = generateTts(responseText.take(500))
                                if (ttsAudio != null && ttsAudio.isNotEmpty()) {
                                    ttsPlaying.set(true)
                                    session.sendJsonEvent("tts_start")
                                    session.send(Frame.Binary(true, ttsAudio))
                                    logger.info { "VOICE_WS: TTS audio sent to client: ${ttsAudio.size} bytes" }
                                }
                            } catch (e: Exception) {
                                logger.warn { "VOICE_WS: TTS failed: ${e.message}" }
                            }
                            // ttsPlaying stays true — client will send tts_finished when done
                        }
                    }

                    session.sendJsonEvent("done")
                } catch (e: Exception) {
                    logger.warn { "VOICE_WS: processing failed: ${e.message}" }
                    session.sendJsonEvent("error", "Chyba: ${e.message?.take(100)}")
                    session.sendJsonEvent("done")
                }
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
                                logger.info { "VOICE_WS: session started source=$source client=$clientId tts=$ttsEnabled" }
                                session.sendJsonEvent("listening")
                                resetIdleTimeout()
                            }

                            "tts_playing" -> ttsPlaying.set(true)
                            "tts_finished" -> {
                                ttsPlaying.set(false)
                                // DON'T reset VAD/accumulator — keep listening continuously
                            }

                            "stop" -> {
                                logger.info { "VOICE_WS: client requested stop" }
                                // Process any remaining audio
                                whisperJob?.join()
                                val remaining = accumulator.flush()
                                if (remaining != null) {
                                    val text2 = transcribeSegment(remaining)
                                    if (text2.isNotBlank()) appendTranscript(text2)
                                }
                                val finalTranscript = getAndClearTranscript()
                                if (finalTranscript.isNotBlank()) {
                                    launchUtteranceProcessing(finalTranscript)
                                    processingJob?.join() // Wait for final processing on stop
                                }
                                break
                            }
                        }
                    }

                    is Frame.Binary -> {
                        if (!started) continue
                        resetIdleTimeout()

                        val pcmBytes = frame.readBytes()
                        val pcm16 = bytesToShorts(pcmBytes)
                        val chunkMs = (pcmBytes.size.toLong() * 1000) / (16000 * 2)

                        // During TTS playback — skip VAD entirely (mic picks up echo)
                        // After TTS finishes (client sends tts_finished), VAD resumes
                        if (ttsPlaying.get()) continue

                        val vadEvent = vad.onChunk(pcm16, chunkMs)

                        when (vadEvent) {
                            EnergyVad.Event.SPEECH_START -> {
                                session.sendJsonEvent("speech_start")
                                utteranceProcessed.set(false)
                            }
                            else -> {}
                        }

                        // Accumulate audio for Whisper transcription
                        val segment = accumulator.addAudio(pcmBytes)
                        if (segment != null) {
                            whisperJob = scope.launch {
                                val text = transcribeSegment(segment)
                                if (text.isNotBlank()) {
                                    appendTranscript(text)
                                    session.sendJsonEvent("transcribing", text)
                                }
                            }
                        }

                        // SPEECH_END — check if we should process
                        if (vadEvent == EnergyVad.Event.SPEECH_END && !utteranceProcessed.get()) {
                            // Wait for any in-flight Whisper job (short wait)
                            whisperJob?.join()

                            // Flush remaining audio
                            val remaining = accumulator.flush()
                            if (remaining != null) {
                                val text = transcribeSegment(remaining)
                                if (text.isNotBlank()) {
                                    appendTranscript(text)
                                    session.sendJsonEvent("transcribing", text)
                                }
                            }

                            val transcript = currentTranscript()

                            // Semantic check — is this a complete thought?
                            if (transcript.isNotBlank() && isCompleteThought(transcript)) {
                                val fullText = getAndClearTranscript()
                                launchUtteranceProcessing(fullText)
                            } else if (transcript.isNotBlank()) {
                                // Not complete yet — keep accumulating, user might continue
                                logger.info { "VOICE_WS: not a complete thought yet: '${transcript.take(80)}'" }
                                // Reset VAD to listen for more speech
                                vad.reset()
                            }
                            // If transcript is blank — just silence, ignore

                            // DON'T send "listening" state change — we never stopped listening
                            accumulator.reset()
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
            processingJob?.cancel()
            ttsJob?.cancel()
            logger.info { "VOICE_WS: session closed source=$source" }
        }
    }

    /** Transcribe a WAV audio segment via Whisper GPU. */
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
            val text = result.text.trim()
            logger.info { "VOICE_WS: Whisper result: '${text.take(100)}' (${wavBytes.size} bytes)" }
            if (isWhisperHallucination(text)) {
                logger.info { "VOICE_WS: filtered hallucination: '$text'" }
                return ""
            }
            return text
        } catch (e: Exception) {
            logger.warn { "VOICE_WS: Whisper failed: ${e.message}" }
            return ""
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    /**
     * Process a complete utterance: send to orchestrator, stream response.
     * Returns the full response text (for TTS).
     *
     * DOES NOT BLOCK the main frame loop — called from background coroutine.
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
    ): String {
        val payload = buildJsonObject {
            put("text", transcript)
            put("source", source)
            put("client_id", clientId)
            put("project_id", projectId)
            put("group_id", groupId)
            put("tts", false)
        }.toString()

        val responseText = StringBuilder()

        try {
            withTimeoutOrNull(30_000) {
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
                                        session.sendJsonEvent("response", text)
                                        break
                                    }
                                    "stored" -> {
                                        val summary = data["summary"]?.jsonPrimitive?.content ?: transcript.take(80)
                                        session.sendJsonEvent("stored", summary)
                                        if (responseText.isEmpty()) responseText.append("Zaznamenáno.")
                                        break
                                    }
                                    "error" -> {
                                        if (responseText.isEmpty()) responseText.append(text)
                                        break
                                    }
                                    "done" -> break
                                }
                            } catch (_: Exception) {}
                            currentEvent = ""
                            currentData = ""
                        }
                    }
                }
            }

            if (responseText.isEmpty()) {
                responseText.append("Omlouvám se, nepodařilo se mi získat odpověď.")
                session.sendJsonEvent("response", responseText.toString())
            }

        } catch (e: Exception) {
            logger.warn { "VOICE_WS: utterance processing failed: ${e.message}" }
            session.sendJsonEvent("error", "Chyba zpracování: ${e.message?.take(100)}")
        }

        return responseText.toString()
    }

    /**
     * Generate TTS audio via self-hosted GPU service (XTTS v2 / F5-TTS).
     * All processing on own GPU — no paid APIs.
     */
    private suspend fun generateTts(text: String): ByteArray? {
        val body = buildJsonObject {
            put("text", text)
            put("language", ttsProperties.language)
            put("speed", ttsProperties.speed.toDouble())
            if (ttsProperties.speakerWav != null) {
                put("voice", ttsProperties.speakerWav)
            }
        }.toString()

        logger.info { "VOICE_WS: TTS request: ${text.take(80)}" }
        val resp = ttsClient.post("${ttsProperties.url}/tts") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val audio = resp.readBytes()
        logger.info { "VOICE_WS: TTS response: ${audio.size} bytes" }
        return if (audio.isNotEmpty()) audio else null
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }
}

/** Pick a natural Czech acknowledgment based on content. */
private fun pickAcknowledgment(transcript: String): String {
    val lower = transcript.lowercase()
    return when {
        lower.contains("?") || lower.contains("kolik") || lower.contains("jaký") ||
            lower.contains("kdy") || lower.contains("jak") || lower.contains("co ") ->
            listOf("Moment, hledám odpověď.", "Podívám se na to.", "Hned zjistím.").random()

        lower.contains("problém") || lower.contains("chyba") || lower.contains("nefunguje") ->
            listOf("Rozumím, analyzuji.", "Podívám se na to.").random()

        lower.contains("udělej") || lower.contains("nastav") || lower.contains("spusť") ||
            lower.contains("vytvoř") || lower.contains("napiš") ->
            listOf("Jasně, připravím to.", "Rozumím, pracuji na tom.").random()

        lower.contains("poznámka") || lower.contains("zapiš") ->
            listOf("Zapisuji.", "Mám to.").random()

        else -> listOf("Rozumím.", "Moment.", "Pracuji na tom.").random()
    }
}

/** Send a JSON text event to the WebSocket client. */
private suspend fun DefaultWebSocketSession.sendJsonEvent(type: String, text: String? = null) {
    val json = if (text != null) {
        """{"type":"$type","text":"${text.replace("\"", "\\\"").replace("\n", " ")}"}"""
    } else {
        """{"type":"$type"}"""
    }
    try {
        send(Frame.Text(json))
    } catch (_: Exception) {
        // Session might be closed
    }
}
