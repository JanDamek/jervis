package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.dto.TaskStateEnum
import com.jervis.repository.TaskRepository
import com.jervis.service.background.TaskService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId

private val logger = KotlinLogging.logger {}

// Default Jervis client/project IDs for Siri queries
private const val DEFAULT_CLIENT_ID = "68a332361b04695a243e5ae8"
private const val DEFAULT_PROJECT_ID = "68a3318f1b04695a243e5adf"

/**
 * Public REST endpoint for Siri / Google Assistant voice queries.
 *
 * Flow: Siri STT → text query → POST /api/v1/chat/siri → create task → poll for result → return response
 *
 * The task goes through the standard pipeline: INDEXING → QUEUED → PROCESSING → DONE/ERROR.
 * We poll with short delays for up to 25 seconds (Siri timeout ~30s).
 */
fun Routing.installSiriChatApi(
    taskRepository: TaskRepository,
    taskService: TaskService,
) {
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

            val clientId = ClientId(ObjectId(body.clientId ?: DEFAULT_CLIENT_ID))
            val projectId = body.projectId?.let { ProjectId(ObjectId(it)) }
                ?: ProjectId(ObjectId(DEFAULT_PROJECT_ID))
            val source = body.source ?: "siri"

            logger.info { "SIRI_CHAT | source=$source | query=${body.query.take(100)}" }

            // Create task — skip KB indexing for fast response
            val task = taskService.createTask(
                taskType = com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING,
                content = body.query,
                clientId = clientId,
                correlationId = "siri-${java.util.UUID.randomUUID().toString().take(8)}",
                sourceUrn = SourceUrn("siri://$source"),
                projectId = projectId,
                state = TaskStateEnum.QUEUED, // Skip INDEXING for faster response
                taskName = "Siri: ${body.query.take(80)}",
            )

            // Poll for completion (max ~25 seconds, Siri timeout is 30s)
            val maxPolls = 50
            val pollInterval = 500L // ms
            var response: String? = null

            for (i in 0 until maxPolls) {
                delay(pollInterval)
                val current = taskRepository.getById(task.id) ?: break

                when (current.state) {
                    TaskStateEnum.DONE -> {
                        // Extract the last agent response from task content
                        response = extractResponse(current.content, body.query)
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
                    else -> continue // Still processing
                }
            }

            val finalResponse = response ?: "Jervis zpracovává váš dotaz na pozadí. Výsledek najdete v aplikaci."

            call.respondText(
                Json.encodeToString(SiriChatResponse.serializer(), SiriChatResponse(
                    response = finalResponse,
                    taskId = task.id.toString(),
                    state = (taskRepository.getById(task.id)?.state ?: TaskStateEnum.PROCESSING).name,
                )),
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
}

/**
 * Extract meaningful response from task content.
 * The task content accumulates during processing — we want the final agent output.
 */
private fun extractResponse(content: String, originalQuery: String): String {
    // If content has been enriched beyond the original query, return the enrichment
    val trimmedContent = content.trim()
    if (trimmedContent == originalQuery.trim()) {
        return "Dotaz byl zpracován."
    }

    // Try to find agent response section
    val markers = listOf("[Agent response]:", "[Odpověď]:", "Výsledek:", "Odpověď:")
    for (marker in markers) {
        val idx = trimmedContent.lastIndexOf(marker)
        if (idx >= 0) {
            return trimmedContent.substring(idx + marker.length).trim().take(500)
        }
    }

    // Return the part after the original query (if content was appended to)
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
)
