package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.dto.TaskStateEnum
import com.jervis.repository.TaskRepository
import com.jervis.service.background.TaskService
import com.jervis.service.meeting.WhisperRestClient
import com.jervis.configuration.properties.WhisperProperties
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.delay
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import java.nio.file.Files

private val logger = KotlinLogging.logger {}

// Default Jervis client/project IDs for Siri queries
private const val DEFAULT_CLIENT_ID = "68a332361b04695a243e5ae8"
private const val DEFAULT_PROJECT_ID = "68a3318f1b04695a243e5adf"

/**
 * Public REST endpoints for Siri / Google Assistant / Watch voice queries.
 *
 * - POST /api/v1/chat/siri — text query → task → poll → response
 * - POST /api/v1/chat/voice — audio upload → Whisper STT → task → poll → response
 */
fun Routing.installSiriChatApi(
    taskRepository: TaskRepository,
    taskService: TaskService,
    whisperRestClient: WhisperRestClient,
    whisperProperties: WhisperProperties,
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

    // Voice (audio) endpoint — Watch/mobile sends audio, backend does STT + chat
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

            // Step 1: Save audio to temp file for Whisper
            val tempFile = Files.createTempFile("voice_", ".wav")
            Files.write(tempFile, audio)

            // Step 2: Transcribe via Whisper REST
            val whisperOptions = """{"model":"${whisperProperties.model}","beam_size":5,"vad_filter":true,"language":"cs"}"""
            val whisperResult = try {
                whisperRestClient.transcribe(
                    baseUrl = whisperProperties.restRemoteUrl,
                    audioFilePath = tempFile.toString(),
                    optionsJson = whisperOptions,
                )
            } finally {
                Files.deleteIfExists(tempFile)
            }

            val transcription = whisperResult.text.trim()
            if (transcription.isEmpty()) {
                call.respondText(
                    """{"response":"Nepodarilo se rozpoznat rec.","transcription":""}""",
                    ContentType.Application.Json,
                )
                return@post
            }

            logger.info { "VOICE_CHAT_STT | source=$source | text=${transcription.take(100)}" }

            // Step 3: Process transcribed text as chat query
            val response = processQuery(transcription, source, null, null, taskService, taskRepository)
            call.respondText(
                Json.encodeToString(SiriChatResponse.serializer(), response.copy(transcription = transcription)),
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
}

/**
 * Create task, poll for completion, return response.
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

    // Poll for completion (max ~25 seconds)
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
    if (trimmedContent == originalQuery.trim()) {
        return "Dotaz byl zpracován."
    }

    val markers = listOf("[Agent response]:", "[Odpověď]:", "Výsledek:", "Odpověď:")
    for (marker in markers) {
        val idx = trimmedContent.lastIndexOf(marker)
        if (idx >= 0) {
            return trimmedContent.substring(idx + marker.length).trim().take(500)
        }
    }

    if (trimmedContent.length > originalQuery.length + 10) {
        val afterQuery = trimmedContent.removePrefix(originalQuery).trim()
        if (afterQuery.isNotBlank()) {
            return afterQuery.take(500)
        }
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
