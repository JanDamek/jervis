package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.dto.task.TaskStateEnum
import com.jervis.task.TaskRepository
import com.jervis.task.TaskService
import com.jervis.chat.ChatService
import com.jervis.meeting.WhisperRestClient
import com.jervis.infrastructure.config.properties.WhisperProperties
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

// ── Voice Session Management ─────────────────────────────────────────────
private data class VoiceSession(
    val id: String,
    val source: String,
    val tts: Boolean,
    val liveAssist: Boolean,
    val meetingId: String?,
    val wearableNotify: Boolean,
    val helperEnabled: Boolean = false,
    val transcript: StringBuilder = StringBuilder(),
    val events: Channel<String> = Channel(Channel.UNLIMITED), // raw SSE strings
)

private val activeSessions = ConcurrentHashMap<String, VoiceSession>()

private const val DEFAULT_CLIENT_ID = "68a332361b04695a243e5ae8"
private const val DEFAULT_PROJECT_ID = "68a3318f1b04695a243e5adf"

// TTS client reference set at installVoiceChatApi entry.
private var TTS_GRPC: com.jervis.infrastructure.grpc.TtsGrpcClient? = null
private var TTS_SPEED: Double = 1.2

/**
 * Public REST endpoints for Siri / Google Assistant / Watch voice queries.
 *
 * - POST /api/v1/chat/siri — text query → task → poll → response
 * - POST /api/v1/chat/voice — audio → Whisper STT → chat orchestrator → TTS + text
 */
fun Routing.installVoiceChatApi(
    taskRepository: TaskRepository,
    taskService: TaskService,
    whisperRestClient: WhisperRestClient,
    whisperProperties: WhisperProperties,
    chatService: ChatService,
    ttsProperties: com.jervis.infrastructure.config.properties.TtsProperties,
    voiceGrpc: com.jervis.infrastructure.grpc.OrchestratorVoiceGrpcClient,
    ttsGrpc: com.jervis.infrastructure.grpc.TtsGrpcClient,
    meetingHelperGrpc: com.jervis.infrastructure.grpc.OrchestratorMeetingHelperGrpcClient,
) {
    // Wire TTS client + speed from configmap.
    TTS_GRPC = ttsGrpc
    TTS_SPEED = ttsProperties.speed.toDouble()

    // ── WebSocket Continuous Voice Session ────────────────────────────────
    // All platforms (Watch, iOS, Android, Desktop) use this single endpoint.
    // Client sends PCM 16kHz chunks (binary) + JSON control (text).
    // Server detects speech boundaries (VAD), transcribes (Whisper GPU),
    // classifies intent, responds (TTS), stores to KB. Session persists.
    val voiceHandler = com.jervis.voice.VoiceWebSocketHandler(whisperRestClient, whisperProperties, ttsProperties, voiceGrpc, ttsGrpc)
    webSocket("/api/v1/voice/ws") {
        voiceHandler.handleSession(this)
    }

    // Text query endpoint (Siri / Google Assistant)
    post("/api/v1/chat/siri") {
        try {
            val body = call.receive<SiriChatRequest>()

            if (body.query.isBlank()) {
                call.respondText(
                    """{"response":"Nerozumel jsem dotazu. Zkuste to znovu."}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            val response = processQuery(body.query, body.source ?: "siri", body.clientId, body.projectId, taskService, taskRepository)
            call.respondText(
                Json.encodeToString(SiriChatResponse.serializer(), response),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "SIRI_CHAT_ERROR" }
            call.respondText(
                """{"response":"Chyba: ${e.message?.replace("\"", "\\\"")?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // Voice endpoint — Watch sends audio → STT → orchestrator chat → TTS + text
    post("/api/v1/chat/voice") {
        try {
            var audioBytes: ByteArray? = null
            var source = "watch"

            val multipart = call.receiveMultipart()
            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        if (part.name == "file" || part.name == "audio") {
                            audioBytes = part.provider().readRemaining().readByteArray()
                        }
                    }
                    is PartData.FormItem -> {
                        if (part.name == "source") source = part.value
                    }
                    else -> {}
                }
                part.dispose()
            }

            val audio = audioBytes
            if (audio == null || audio.isEmpty()) {
                call.respondText(
                    """{"response":"Zadne audio nebylo prijato."}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            logger.info { "VOICE_CHAT | source=$source | audioSize=${audio.size}" }

            // Step 1: Whisper STT
            val tempFile = Files.createTempFile("voice_", ".wav")
            Files.write(tempFile, audio)

            // GPU Whisper on VD — fail-fast, no CPU fallback.
            // vad_filter=false for short recordings (watch audio is typically 2-10s).
            val whisperOptions = com.jervis.contracts.whisper.TranscribeOptions.newBuilder()
                .setModel(whisperProperties.model)
                .setBeamSize(1)
                .setVadFilter(false)
                .setLanguage("cs")
                .build()
            val whisperResult = try {
                whisperRestClient.transcribe(
                    baseUrl = whisperProperties.restRemoteUrl,
                    audioFilePath = tempFile.toString(),
                    options = whisperOptions,
                )
            } finally {
                Files.deleteIfExists(tempFile)
            }

            val transcription = whisperResult.text.trim()
            if (transcription.isEmpty()) {
                val errorMsg = "Nepodařilo se rozpoznat řeč. Zkuste to znovu."
                val tts = try { generateTtsAudio(errorMsg) } catch (_: Exception) { null }
                call.respondText(
                    Json.encodeToString(VoiceChatResponse.serializer(), VoiceChatResponse(
                        response = errorMsg,
                        transcription = "",
                        ttsAudio = tts,
                        complete = true,
                    )),
                    ContentType.Application.Json,
                )
                return@post
            }

            logger.info { "VOICE_CHAT_STT | source=$source | text=${transcription.take(100)}" }

            // Step 2: Fire-and-forget — send to Python voice pipeline in background
            // Watch needs immediate response, processing happens async.
            // Python pipeline handles: intent classify → KB store → orchestrator.
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    voiceGrpc.process(
                        text = transcription,
                        source = source,
                        clientId = DEFAULT_CLIENT_ID,
                        projectId = DEFAULT_PROJECT_ID,
                        tts = false,
                    ).collect { /* drain stream so pipeline completes */ }
                    logger.info { "VOICE_BG_COMPLETE | source=$source | text=${transcription.take(60)}" }
                } catch (e: Exception) {
                    logger.warn(e) { "VOICE_BG_PIPELINE_ERROR | source=$source" }
                }
            }

            // Step 3: Immediate response — confirm receipt + TTS
            val responseText = "Rozumím. ${transcription.take(80)}"
            val ttsAudioBase64 = try {
                generateTtsAudio(responseText.take(300))
            } catch (e: Exception) {
                logger.warn(e) { "VOICE_TTS_FAILED" }
                null
            }

            logger.info { "VOICE_CHAT_IMMEDIATE | text=${responseText.take(80)}" }

            call.respondText(
                Json.encodeToString(VoiceChatResponse.serializer(), VoiceChatResponse(
                    response = responseText,
                    transcription = transcription,
                    ttsAudio = ttsAudioBase64,
                    complete = true,
                )),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "VOICE_CHAT_ERROR" }
            call.respondText(
                """{"response":"Chyba: ${e.message?.replace("\"", "\\\"")?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // ── SSE Streaming Voice Chat ─────────────────────────────────────────
    // Watch sends audio (multipart), server streams SSE events back in real-time:
    //   transcribing → transcribed → responding → token* → tts_audio → done
    post("/api/v1/voice/stream") {
        // Parse multipart audio first (before starting SSE response)
        var audioBytes: ByteArray? = null
        var source = "watch"
        var tts = true

        val multipart = call.receiveMultipart()
        multipart.forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    if (part.name == "file" || part.name == "audio") {
                        audioBytes = part.provider().readRemaining().readByteArray()
                    }
                }
                is PartData.FormItem -> {
                    when (part.name) {
                        "source" -> source = part.value
                        "tts" -> tts = part.value.toBooleanStrictOrNull() ?: true
                    }
                }
                else -> {}
            }
            part.dispose()
        }

        val audio = audioBytes
        if (audio == null || audio.isEmpty()) {
            call.respondText("""event: error\ndata: {"text":"Zadne audio"}\n\n""", ContentType.Text.EventStream)
            return@post
        }

        logger.info { "VOICE_STREAM | source=$source | tts=$tts | audioSize=${audio.size}" }

        // Stream SSE events back as each pipeline step completes
        call.response.headers.append("Cache-Control", "no-cache, no-store")
        call.response.headers.append("X-Accel-Buffering", "no") // nginx: don't buffer SSE
        call.response.headers.append("Connection", "keep-alive")
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            // Helper to emit SSE event
            suspend fun sse(event: String, data: String) {
                write("event: $event\ndata: $data\n\n")
                flush()
            }

            try {
                // Step 1: Whisper STT
                sse("transcribing", """{"text":"Přepisuji řeč..."}""")

                val tempFile = Files.createTempFile("voice_stream_", ".wav")
                Files.write(tempFile, audio)

                // GPU Whisper on VD — fail-fast, no CPU fallback.
                val whisperOpts = com.jervis.contracts.whisper.TranscribeOptions.newBuilder()
                    .setModel(whisperProperties.model)
                    .setBeamSize(1)
                    .setVadFilter(false)
                    .setLanguage("cs")
                    .build()

                val whisperResult = try {
                    whisperRestClient.transcribe(whisperProperties.restRemoteUrl, tempFile.toString(), whisperOpts) { percent, segments, elapsed, lastText ->
                        if (lastText != null && lastText.isNotBlank()) {
                            sse("transcribing", """{"text":"${lastText.escapeJson()}","percent":$percent}""")
                        }
                    }
                } finally {
                    Files.deleteIfExists(tempFile)
                }

                val transcription = whisperResult.text.trim()
                if (transcription.isEmpty()) {
                    sse("error", """{"text":"Nepodařilo se rozpoznat řeč."}""")
                    sse("done", "{}")
                    return@respondTextWriter
                }

                logger.info { "VOICE_STREAM_STT | text=${transcription.take(100)}" }
                sse("transcribed", """{"text":"${transcription.escapeJson()}"}""")

                // Step 2: Forward to Python voice pipeline — intent classification + response
                logger.info { "VOICE_STREAM_FORWARD | text=${transcription.take(80)}" }

                val responseBuilder = StringBuilder()

                try {
                    voiceGrpc.process(
                        text = transcription,
                        source = source,
                        clientId = DEFAULT_CLIENT_ID,
                        projectId = DEFAULT_PROJECT_ID,
                        tts = tts,
                    ).collect { event ->
                        val mapped = event.toSseEvent() ?: return@collect
                        sse(mapped.name, mapped.dataJson)
                        when (event.payloadCase) {
                            com.jervis.contracts.orchestrator.VoiceStreamEvent.PayloadCase.TOKEN ->
                                responseBuilder.append(event.token.text)
                            com.jervis.contracts.orchestrator.VoiceStreamEvent.PayloadCase.RESPONSE ->
                                if (responseBuilder.isEmpty()) responseBuilder.append(event.response.text)
                            else -> {}
                        }
                    }
                } catch (e: Exception) {
                    logger.warn { "VOICE_STREAM_PYTHON_ERROR: ${e.message}" }
                    sse("error", """{"text":"Chyba zpracování: ${e.message?.take(80)?.escapeJson() ?: ""}"}""")
                }

                // Step 3: TTS streaming — sentence by sentence (if tts enabled)
                val responseText = responseBuilder.toString().trim()
                if (tts && responseText.isNotBlank()) {
                    logger.info { "VOICE_STREAM_TTS_START | text=${responseText.take(50)}" }
                    try {
                        val sentences = responseText.take(500).split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
                        for (sentence in sentences) {
                            val ttsAudio = generateTtsAudio(sentence)
                            if (ttsAudio != null) {
                                sse("tts_audio", """{"data":"$ttsAudio"}""")
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn { "VOICE_STREAM_TTS_FAILED: ${e::class.simpleName}: ${e.message}" }
                    }
                }

                sse("done", "{}")

            } catch (e: Exception) {
                logger.error(e) { "VOICE_STREAM_ERROR" }
                try {
                    write("event: error\ndata: {\"text\":\"${e.message?.take(100)?.escapeJson() ?: "Chyba"}\"}\n\n")
                    flush()
                } catch (_: Exception) {}
            }
        }
    }

    // ── TTS Stream endpoint — read any text aloud ─────────────────────────
    // POST /api/v1/tts/stream — accepts JSON {text}, returns SSE with tts_audio chunks per sentence
    // Simple TTS batch — JSON in, JSON out with base64 WAV audio
    post("/api/v1/tts") {
        try {
            val body = call.receive<TtsStreamRequest>()
            if (body.text.isBlank()) {
                call.respondText("""{"error":"empty"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            logger.info { "TTS_BATCH | text=${body.text.take(50)}" }
            val audio = generateTtsAudio(body.text)
            call.respondText(
                Json.encodeToString(VoiceChatResponse.serializer(), VoiceChatResponse(
                    response = body.text,
                    ttsAudio = audio,
                    complete = true,
                )),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn { "TTS_BATCH_ERROR: ${e.message}" }
            call.respondText("""{"error":"${e.message?.take(100)}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
        }
    }

    // TTS Stream — forwards gRPC SpeakStream chunks as SSE tts_pcm events.
    post("/api/v1/tts/stream") {
        val body = call.receive<TtsStreamRequest>()
        if (body.text.isBlank()) {
            call.respondText("""{"error":"empty text"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }

        logger.info { "TTS_STREAM | text=${body.text.take(50)} | speed=${body.speed}" }

        call.response.headers.append("Cache-Control", "no-cache, no-store")
        call.response.headers.append("X-Accel-Buffering", "no")
        call.response.headers.append("Connection", "keep-alive")
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            suspend fun sse(event: String, data: String) { write("event: $event\ndata: $data\n\n"); flush() }

            val grpc = TTS_GRPC
            if (grpc == null) {
                sse("error", """{"text":"TTS client not wired"}""")
                return@respondTextWriter
            }
            try {
                grpc.speakStream(body.text, speed = body.speed.toDouble()).collect { chunk ->
                    val b64 = Base64.getEncoder().encodeToString(chunk.data.toByteArray())
                    sse("tts_pcm", """{"data":"$b64"}""")
                    if (chunk.isLast) sse("done", "{}")
                }
            } catch (e: Exception) {
                logger.warn { "TTS_STREAM_ERROR: ${e::class.simpleName}: ${e.message}" }
                try { sse("error", """{"text":"${e.message?.take(100)?.escapeJson() ?: "Chyba"}"}""") } catch (_: Exception) {}
            }
        }
    }

    // /api/v1/voice/speaker — removed. The previous XTTS /upload_speaker
    // endpoint no longer exists on the Piper-based TTS service; voice
    // cloning will return once a gRPC RegisterSpeaker RPC lands.

    // ── Session-based voice streaming (5s chunks during recording) ──────────
    // POST /api/v1/voice/session — start SSE session, receive events for chunks
    post("/api/v1/voice/session") {
        val body = call.receive<VoiceSessionRequest>()
        val sessionId = UUID.randomUUID().toString().take(12)
        val session = VoiceSession(
            id = sessionId,
            source = body.source ?: "app_chat",
            tts = body.tts ?: true,
            liveAssist = body.liveAssist ?: false,
            meetingId = body.meetingId,
            wearableNotify = body.wearableNotify ?: false,
            helperEnabled = body.helperEnabled ?: false,
        )
        activeSessions[sessionId] = session
        logger.info { "VOICE_SESSION_START | id=$sessionId | source=${session.source} | liveAssist=${session.liveAssist}" }

        // Long-lived SSE connection — receives events from chunk processing
        call.response.headers.append("Cache-Control", "no-cache, no-store")
        call.response.headers.append("X-Accel-Buffering", "no")
        call.response.headers.append("Connection", "keep-alive")
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            // Send session ID as first event
            write("event: session_started\ndata: {\"session_id\":\"$sessionId\"}\n\n")
            flush()

            try {
                for (rawSse in session.events) {
                    write(rawSse)
                    flush()
                }
            } finally {
                activeSessions.remove(sessionId)
                logger.info { "VOICE_SESSION_CLOSED | id=$sessionId" }
            }
        }
    }

    // POST /api/v1/voice/session/chunk — send audio chunk for transcription
    post("/api/v1/voice/session/chunk") {
        val sessionId = call.request.queryParameters["sessionId"]
        val session = sessionId?.let { activeSessions[it] }
        if (session == null) {
            call.respondText("""{"error":"invalid session"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }

        var audioBytes: ByteArray? = null
        var chunkIndex = 0

        val multipart = call.receiveMultipart()
        multipart.forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    if (part.name == "file" || part.name == "audio") {
                        audioBytes = part.provider().readRemaining().readByteArray()
                    }
                }
                is PartData.FormItem -> {
                    if (part.name == "chunk") chunkIndex = part.value.toIntOrNull() ?: 0
                }
                else -> {}
            }
            part.dispose()
        }

        val audio = audioBytes
        if (audio == null || audio.isEmpty()) {
            call.respondText("""{"error":"empty chunk"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }

        logger.info { "VOICE_CHUNK | session=$sessionId | chunk=$chunkIndex | bytes=${audio.size}" }

        // GPU Whisper on VD — fail-fast, no CPU fallback.
        val tempFile = Files.createTempFile("chunk_${sessionId}_", ".wav")
        Files.write(tempFile, audio)

        try {
            val opts = com.jervis.contracts.whisper.TranscribeOptions.newBuilder()
                .setModel(whisperProperties.model)
                .setBeamSize(1)
                .setVadFilter(false)
                .setLanguage("cs")
                .build()
            val result = whisperRestClient.transcribe(whisperProperties.restRemoteUrl, tempFile.toString(), opts)
            val chunkText = result.text.trim()

            if (chunkText.isNotBlank()) {
                session.transcript.append(chunkText).append(" ")
                session.events.trySend("event: chunk_transcribed\ndata: {\"text\":\"${chunkText.escapeJson()}\",\"chunk\":$chunkIndex,\"full_text\":\"${session.transcript.toString().trim().escapeJson()}\"}\n\n")

                // Live assist: search KB for hints
                if (session.liveAssist) {
                    try {
                        val hintResp = voiceGrpc.hint(
                            text = chunkText,
                            clientId = DEFAULT_CLIENT_ID,
                            projectId = DEFAULT_PROJECT_ID,
                        )
                        val hintText = hintResp.hint
                        if (hintText.isNotBlank()) {
                            session.events.trySend("event: hint\ndata: {\"text\":\"${hintText.escapeJson()}\",\"push_to_wearable\":${session.wearableNotify}}\n\n")
                        }
                    } catch (e: Exception) {
                        logger.warn { "VOICE_HINT_ERROR: ${e.message}" }
                    }
                }

                // Meeting Helper: forward transcript to helper pipeline for translation + suggestions
                if (session.helperEnabled && !session.meetingId.isNullOrBlank()) {
                    try {
                        meetingHelperGrpc.chunk(
                            meetingId = session.meetingId,
                            text = chunkText,
                            speaker = "",
                        )
                    } catch (e: Exception) {
                        logger.warn { "MEETING_HELPER_CHUNK_ERROR: ${e.message}" }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn { "VOICE_CHUNK_STT_ERROR: ${e.message}" }
            session.events.trySend("event: error\ndata: {\"text\":\"Chunk $chunkIndex: ${e.message?.take(60)?.escapeJson() ?: "STT error"}\"}\n\n")
        } finally {
            Files.deleteIfExists(tempFile)
        }

        call.respondText("""{"ok":true,"chunk":$chunkIndex}""", ContentType.Application.Json)
    }

    // POST /api/v1/voice/session/stop — finalize session, process full transcript
    post("/api/v1/voice/session/stop") {
        val sessionId = call.request.queryParameters["sessionId"]
        val session = sessionId?.let { activeSessions[it] }
        if (session == null) {
            call.respondText("""{"error":"invalid session"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }

        logger.info { "VOICE_SESSION_STOP | id=$sessionId | transcript_len=${session.transcript.length}" }

        val fullTranscript = session.transcript.toString().trim()
        if (fullTranscript.isBlank()) {
            session.events.trySend("event: error\ndata: {\"text\":\"Žádný text nebyl rozpoznán.\"}\n\n")
            session.events.trySend("event: done\ndata: {}\n\n")
            session.events.close()
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
            return@post
        }

        session.events.trySend("event: transcribed\ndata: {\"text\":\"${fullTranscript.escapeJson()}\",\"is_final\":true}\n\n")

        // Forward full transcript to voice pipeline
        try {
            val responseBuilder = StringBuilder()
            voiceGrpc.process(
                text = fullTranscript,
                source = session.source,
                clientId = DEFAULT_CLIENT_ID,
                projectId = DEFAULT_PROJECT_ID,
                tts = session.tts,
                isFinal = true,
            ).collect { event ->
                val mapped = event.toSseEvent() ?: return@collect
                session.events.trySend("event: ${mapped.name}\ndata: ${mapped.dataJson}\n\n")
                when (event.payloadCase) {
                    com.jervis.contracts.orchestrator.VoiceStreamEvent.PayloadCase.TOKEN ->
                        responseBuilder.append(event.token.text)
                    com.jervis.contracts.orchestrator.VoiceStreamEvent.PayloadCase.RESPONSE ->
                        if (responseBuilder.isEmpty()) responseBuilder.append(event.response.text)
                    else -> {}
                }
            }

            // TTS for final response
            if (session.tts) {
                val responseText = responseBuilder.toString().trim()
                if (responseText.isNotBlank()) {
                    try {
                        val sentences = responseText.take(500).split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
                        for (sentence in sentences) {
                            val ttsAudio = generateTtsAudio(sentence)
                            if (ttsAudio != null) {
                                session.events.trySend("event: tts_audio\ndata: {\"data\":\"$ttsAudio\"}\n\n")
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn { "VOICE_SESSION_TTS_ERROR: ${e.message}" }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn { "VOICE_SESSION_PROCESS_ERROR: ${e.message}" }
            session.events.trySend("event: error\ndata: {\"text\":\"${e.message?.take(80)?.escapeJson() ?: "Chyba"}\"}\n\n")
        }

        session.events.trySend("event: done\ndata: {}\n\n")
        session.events.close()
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }
}

@Serializable
data class VoiceSessionRequest(
    val source: String? = null,
    val tts: Boolean? = null,
    val liveAssist: Boolean? = null,
    val meetingId: String? = null,
    val wearableNotify: Boolean? = null,
    val helperEnabled: Boolean? = null,
)

@Serializable
data class TtsStreamRequest(val text: String, val speed: Float = 1.2f)

/** Escape JSON special chars for SSE data. */
/** Map a typed VoiceStreamEvent into an SSE-friendly `(eventName, dataJson)` pair.
 *  Returns null for the reserved `PAYLOAD_NOT_SET` case so callers can skip empty events. */
internal data class VoiceSseEvent(val name: String, val dataJson: String)

internal fun com.jervis.contracts.orchestrator.VoiceStreamEvent.toSseEvent(): VoiceSseEvent? {
    fun escape(s: String): String = s.escapeJson()
    return when (payloadCase) {
        com.jervis.contracts.orchestrator.VoiceStreamEvent.PayloadCase.RESPONDING ->
            VoiceSseEvent("responding", "{}")
        com.jervis.contracts.orchestrator.VoiceStreamEvent.PayloadCase.TOKEN ->
            VoiceSseEvent("token", """{"text":"${escape(token.text)}"}""")
        com.jervis.contracts.orchestrator.VoiceStreamEvent.PayloadCase.RESPONSE ->
            VoiceSseEvent(
                "response",
                """{"text":"${escape(response.text)}","complete":${response.complete}}""",
            )
        com.jervis.contracts.orchestrator.VoiceStreamEvent.PayloadCase.STORED ->
            VoiceSseEvent(
                "stored",
                """{"kind":"${escape(stored.kind)}","summary":"${escape(stored.summary)}"}""",
            )
        com.jervis.contracts.orchestrator.VoiceStreamEvent.PayloadCase.DONE ->
            VoiceSseEvent("done", "{}")
        com.jervis.contracts.orchestrator.VoiceStreamEvent.PayloadCase.ERROR ->
            VoiceSseEvent("error", """{"text":"${escape(error.text)}"}""")
        com.jervis.contracts.orchestrator.VoiceStreamEvent.PayloadCase.PAYLOAD_NOT_SET -> null
        else -> null
    }
}

private fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

/**
 * Send message to orchestrator via ChatService and collect response tokens.
 * Waits max 15 seconds — if orchestrator finishes in time, returns full response.
 * Otherwise returns partial response collected so far.
 */
private data class ChatResult(val text: String, val complete: Boolean)

private suspend fun collectChatResponse(
    chatService: ChatService,
    message: String,
    source: String,
): ChatResult {
    val responseBuilder = StringBuilder()
    var complete = false

    try {
        val eventFlow = chatService.sendMessage(
            userId = "jan",
            text = "$message",
            activeClientId = DEFAULT_CLIENT_ID,
            activeProjectId = DEFAULT_PROJECT_ID,
            maxOpenRouterTier = "FREE", // Watch needs fast response — allow cloud models
        )

        // Collect tokens with 45s timeout — orchestrator needs time for KB search + LLM
        withTimeoutOrNull(45_000) {
            eventFlow.collect { event ->
                when (event.type) {
                    "token" -> responseBuilder.append(event.content)
                    "done" -> {
                        complete = true
                        return@collect
                    }
                    "error" -> {
                        if (responseBuilder.isEmpty()) {
                            responseBuilder.append("Došlo k chybě při zpracování.")
                        }
                        complete = true
                        return@collect
                    }
                }
            }
        }
    } catch (e: Exception) {
        logger.warn(e) { "VOICE_CHAT_COLLECT_ERROR" }
        if (responseBuilder.isEmpty()) {
            responseBuilder.append("Zpracovávám na pozadí.")
        }
    }

    return ChatResult(
        text = responseBuilder.toString().trim(),
        complete = complete,
    )
}

/**
 * Generate TTS audio (WAV) from text, return Base64 encoded.
 * Dials TtsService.Speak over gRPC.
 */
private suspend fun generateTtsAudio(text: String): String? {
    val grpc = TTS_GRPC ?: return null
    val audioBytes = try {
        grpc.speak(text, speed = TTS_SPEED)
    } catch (e: Exception) {
        return null
    }
    if (audioBytes.isEmpty()) return null
    return Base64.getEncoder().encodeToString(audioBytes)
}

/**
 * Create task, poll for completion, return response.
 * Used by /chat/siri text endpoint (Siri has ~30s timeout).
 */
private suspend fun processQuery(
    query: String,
    source: String,
    clientIdStr: String?,
    projectIdStr: String?,
    taskService: TaskService,
    taskRepository: TaskRepository,
): SiriChatResponse {
    val clientId = ClientId(ObjectId(clientIdStr ?: DEFAULT_CLIENT_ID))
    val projectId = ProjectId(ObjectId(projectIdStr ?: DEFAULT_PROJECT_ID))

    logger.info { "SIRI_CHAT | source=$source | query=${query.take(100)}" }

    val task = taskService.createTask(
        taskType = com.jervis.dto.task.TaskTypeEnum.INSTANT,
        content = query,
        clientId = clientId,
        correlationId = "siri-${java.util.UUID.randomUUID().toString().take(8)}",
        sourceUrn = SourceUrn("siri://$source"),
        projectId = projectId,
        state = TaskStateEnum.QUEUED,
        taskName = "Siri: ${query.take(80)}",
    )

    val maxPolls = 50
    val pollInterval = 500L
    var response: String? = null

    for (i in 0 until maxPolls) {
        delay(pollInterval)
        val current = taskRepository.getById(task.id) ?: break

        when (current.state) {
            TaskStateEnum.DONE -> {
                response = extractResponse(current.content, query)
                break
            }
            TaskStateEnum.ERROR -> {
                response = current.errorMessage ?: "Doslo k chybe pri zpracovani."
                break
            }
            TaskStateEnum.USER_TASK -> {
                response = current.pendingUserQuestion ?: "Jervis se ptá — otevřete aplikaci pro odpověď."
                break
            }
            else -> continue
        }
    }

    return SiriChatResponse(
        response = response ?: "Jervis zpracovává váš dotaz na pozadí. Výsledek najdete v aplikaci.",
        taskId = task.id.toString(),
        state = (taskRepository.getById(task.id)?.state ?: TaskStateEnum.PROCESSING).name,
    )
}

private fun extractResponse(content: String, originalQuery: String): String {
    val trimmedContent = content.trim()
    if (trimmedContent == originalQuery.trim()) return "Dotaz byl zpracován."

    val markers = listOf("[Agent response]:", "[Odpověď]:", "Výsledek:", "Odpověď:")
    for (marker in markers) {
        val idx = trimmedContent.lastIndexOf(marker)
        if (idx >= 0) return trimmedContent.substring(idx + marker.length).trim().take(500)
    }

    if (trimmedContent.length > originalQuery.length + 10) {
        val afterQuery = trimmedContent.removePrefix(originalQuery).trim()
        if (afterQuery.isNotBlank()) return afterQuery.take(500)
    }

    return trimmedContent.take(500)
}

@Serializable
data class SiriChatRequest(
    val query: String,
    val source: String? = null,
    val clientId: String? = null,
    val projectId: String? = null,
)

@Serializable
data class SiriChatResponse(
    val response: String,
    val taskId: String? = null,
    val state: String? = null,
    val transcription: String? = null,
)

/** Voice endpoint response — text + optional TTS audio as Base64 WAV. */
@Serializable
data class VoiceChatResponse(
    val response: String,
    val transcription: String? = null,
    val ttsAudio: String? = null, // Base64-encoded WAV
    val complete: Boolean = false,
)
