package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.dto.TaskStateEnum
import com.jervis.repository.TaskRepository
import com.jervis.service.background.TaskService
import com.jervis.service.chat.ChatService
import com.jervis.service.meeting.WhisperRestClient
import com.jervis.configuration.properties.WhisperProperties
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
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
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import java.nio.file.Files
import java.util.Base64

private val logger = KotlinLogging.logger {}

private const val DEFAULT_CLIENT_ID = "68a332361b04695a243e5ae8"
private const val DEFAULT_PROJECT_ID = "68a3318f1b04695a243e5adf"

// TTS service (K8s internal)
private const val TTS_URL = "http://jervis-tts:8787/tts"

private val ttsClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 5_000
    }
}

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
) {
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

            // Watch uses CPU Whisper (K8s internal, always available) with medium model.
            // vad_filter=false for short recordings (watch audio is typically 2-10s).
            // Falls back to GPU Whisper if CPU service is unavailable.
            val cpuWhisperUrl = "http://jervis-whisper-cpu:8786"
            val whisperOptions = """{"model":"medium","beam_size":1,"vad_filter":false,"language":"cs"}"""
            val gpuWhisperOptions = """{"model":"${whisperProperties.model}","beam_size":1,"vad_filter":true,"language":"cs"}"""
            val whisperResult = try {
                // Try CPU Whisper first (always available, no GPU contention)
                whisperRestClient.transcribe(
                    baseUrl = cpuWhisperUrl,
                    audioFilePath = tempFile.toString(),
                    optionsJson = whisperOptions,
                )
            } catch (cpuError: Exception) {
                logger.warn { "VOICE_WHISPER_CPU_FAILED: ${cpuError.message}, falling back to GPU" }
                // Fallback to GPU Whisper
                whisperRestClient.transcribe(
                    baseUrl = whisperProperties.restRemoteUrl,
                    audioFilePath = tempFile.toString(),
                    optionsJson = gpuWhisperOptions,
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

            // Step 2: Send to orchestrator via chat — collect SSE tokens (max 15s)
            val chatResponse = collectChatResponse(chatService, transcription, source)

            logger.info { "VOICE_CHAT_RESPONSE | complete=${chatResponse.complete} | text=${chatResponse.text.take(100)}" }

            // Step 3: Build response text
            val responseText = when {
                chatResponse.complete && chatResponse.text.isNotBlank() -> chatResponse.text
                chatResponse.text.isNotBlank() -> "${chatResponse.text} ...detaily v aplikaci."
                else -> "Rozumím: ${transcription.take(80)}. Zpracovávám, výsledek najdete v aplikaci."
            }

            logger.info { "VOICE_CHAT_FINAL | responseText=${responseText.take(100)}" }

            // Step 4: Generate TTS audio (best effort)
            val ttsAudioBase64 = try {
                generateTtsAudio(responseText.take(300))
            } catch (e: Exception) {
                logger.warn(e) { "VOICE_TTS_FAILED" }
                null
            }

            call.respondText(
                Json.encodeToString(VoiceChatResponse.serializer(), VoiceChatResponse(
                    response = responseText,
                    transcription = transcription,
                    ttsAudio = ttsAudioBase64,
                    complete = chatResponse.complete,
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
            call.respondText("""event: error\ndata: {"text":"Zadne audio"}\n\n""", ContentType.Text.EventStream)
            return@post
        }

        logger.info { "VOICE_STREAM | source=$source | audioSize=${audio.size}" }

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

                val cpuWhisperUrl = "http://jervis-whisper-cpu:8786"
                val whisperOpts = """{"model":"medium","beam_size":1,"vad_filter":false,"language":"cs"}"""
                val gpuWhisperOpts = """{"model":"${whisperProperties.model}","beam_size":1,"vad_filter":false,"language":"cs"}"""

                val whisperResult = try {
                    whisperRestClient.transcribe(cpuWhisperUrl, tempFile.toString(), whisperOpts) { percent, segments, elapsed, lastText ->
                        if (lastText != null && lastText.isNotBlank()) {
                            sse("transcribing", """{"text":"${lastText.escapeJson()}","percent":$percent}""")
                        }
                    }
                } catch (cpuError: Exception) {
                    logger.warn { "VOICE_STREAM_CPU_FAILED: ${cpuError.message}, fallback GPU" }
                    whisperRestClient.transcribe(whisperProperties.restRemoteUrl, tempFile.toString(), gpuWhisperOpts) { percent, _, _, lastText ->
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

                // Step 2: Orchestrator — stream response tokens
                sse("responding", """{"text":"Generuji odpověď..."}""")

                val responseBuilder = StringBuilder()
                var chatComplete = false

                try {
                    val eventFlow = chatService.sendMessage(
                        userId = "jan",
                        text = "$transcription",
                        activeClientId = DEFAULT_CLIENT_ID,
                        activeProjectId = DEFAULT_PROJECT_ID,
                        maxOpenRouterTier = "FREE",
                    )

                    withTimeoutOrNull(45_000) {
                        eventFlow.collect { event ->
                            when (event.type) {
                                "token" -> {
                                    responseBuilder.append(event.content)
                                    sse("token", """{"text":"${event.content.escapeJson()}"}""")
                                }
                                "done" -> {
                                    chatComplete = true
                                    return@collect
                                }
                                "error" -> {
                                    chatComplete = true
                                    return@collect
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn { "VOICE_STREAM_CHAT_ERROR: ${e.message}" }
                }

                val responseText = responseBuilder.toString().trim().ifBlank {
                    "Zpracovávám dotaz. Výsledek najdete v aplikaci."
                }

                logger.info { "VOICE_STREAM_RESPONSE | complete=$chatComplete | text=${responseText.take(100)}" }

                // Send response text first
                sse("response", """{"text":"${responseText.escapeJson()}","complete":$chatComplete}""")

                // Step 3: TTS streaming — sentence by sentence for low latency
                logger.info { "VOICE_STREAM_TTS_START | text=${responseText.take(50)}" }
                try {
                    // Split into sentences and TTS each one separately
                    val sentences = responseText.take(500).split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
                    for (sentence in sentences) {
                        val ttsAudio = generateTtsAudio(sentence)
                        if (ttsAudio != null) {
                            sse("tts_audio", """{"data":"$ttsAudio"}""")
                            logger.info { "VOICE_STREAM_TTS_CHUNK | sentence=${sentence.take(40)} | audioSize=${ttsAudio.length}" }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn { "VOICE_STREAM_TTS_FAILED: ${e::class.simpleName}: ${e.message}" }
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

    post("/api/v1/tts/stream") {
        val body = call.receive<TtsStreamRequest>()
        if (body.text.isBlank()) {
            call.respondText("""{"error":"empty text"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }

        call.response.headers.append("Cache-Control", "no-cache, no-store")
        call.response.headers.append("X-Accel-Buffering", "no")
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            suspend fun sse(event: String, data: String) { write("event: $event\ndata: $data\n\n"); flush() }

            try {
                val sentences = body.text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
                for (sentence in sentences) {
                    val ttsAudio = generateTtsAudio(sentence)
                    if (ttsAudio != null) {
                        sse("tts_audio", """{"data":"$ttsAudio","sentence":"${sentence.escapeJson()}"}""")
                    }
                }
                sse("done", "{}")
            } catch (e: Exception) {
                logger.warn { "TTS_STREAM_ERROR: ${e.message}" }
                try { sse("error", """{"text":"${e.message?.take(100)?.escapeJson() ?: "Chyba"}"}""") } catch (_: Exception) {}
            }
        }
    }
}

@Serializable
data class TtsStreamRequest(val text: String, val speed: Float = 1.7f)

/** Escape JSON special chars for SSE data. */
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
 */
private suspend fun generateTtsAudio(text: String): String? {
    val response = ttsClient.post(TTS_URL) {
        contentType(ContentType.Application.Json)
        setBody("""{"text":"${text.replace("\"", "\\\"").replace("\n", " ")}","speed":1.7}""")
    }

    val audioBytes = response.readBytes()
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
        taskType = com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING,
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
