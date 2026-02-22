package com.jervis.service.chat

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

private val jsonParser = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * HTTP SSE client for Python /chat endpoint.
 *
 * Sends a ChatRequest to Python, reads SSE event stream,
 * and yields ChatStreamEvent objects for Kotlin to forward to UI.
 *
 * SSE format from Python (sse-starlette):
 * ```
 * event: token
 * data: {"type":"token","content":"Hello","metadata":{}}
 *
 * event: done
 * data: {"type":"done","content":"","metadata":{...}}
 * ```
 */
@Component
class PythonChatClient(
    @Value("\${endpoints.orchestrator.baseUrl:http://localhost:8090}") private val orchestratorBaseUrl: String,
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                },
            )
        }
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE      // No timeout — agentic loop can be slow
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = Long.MAX_VALUE        // SSE stream can be long-lived
        }
    }

    /**
     * Send a chat message to Python /chat and stream SSE events back.
     *
     * @return Flow of ChatStreamEvent (token, tool_call, tool_result, done, error)
     */
    fun chat(
        sessionId: String,
        message: String,
        messageSequence: Long,
        userId: String = "jan",
        activeClientId: String? = null,
        activeProjectId: String? = null,
        contextTaskId: String? = null,
    ): Flow<ChatStreamEvent> = flow {
        val apiUrl = "${orchestratorBaseUrl.trimEnd('/')}/chat"
        val request = PythonChatRequest(
            sessionId = sessionId,
            message = message,
            messageSequence = messageSequence.toInt(),
            userId = userId,
            activeClientId = activeClientId,
            activeProjectId = activeProjectId,
            contextTaskId = contextTaskId,
        )

        logger.info { "PYTHON_CHAT_START | session=$sessionId | message=${message.take(80)}" }

        try {
            client.preparePost(apiUrl) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.execute { response ->
                val channel = response.bodyAsChannel()

                var currentEventType = ""
                var currentData = StringBuilder()

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break

                    when {
                        line.startsWith("event:") -> {
                            currentEventType = line.removePrefix("event:").trim()
                        }
                        line.startsWith("data:") -> {
                            currentData.append(line.removePrefix("data:").trim())
                        }
                        line.isBlank() -> {
                            // Empty line = end of SSE event
                            if (currentData.isNotEmpty()) {
                                val event = parseSSEData(currentEventType, currentData.toString())
                                if (event != null) {
                                    emit(event)

                                    // Stop on terminal events
                                    if (event.type == "done" || event.type == "error") {
                                        logger.info { "PYTHON_CHAT_END | session=$sessionId | type=${event.type}" }
                                        return@execute
                                    }
                                }
                                currentData = StringBuilder()
                                currentEventType = ""
                            }
                        }
                    }
                }

                // Handle remaining data if stream closed without trailing empty line
                if (currentData.isNotEmpty()) {
                    val event = parseSSEData(currentEventType, currentData.toString())
                    if (event != null) {
                        emit(event)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "PYTHON_CHAT_ERROR | session=$sessionId | error=${e.message}" }
            emit(ChatStreamEvent(type = "error", content = "Connection to orchestrator failed: ${e.message}"))
        }
    }

    private fun parseSSEData(eventType: String, data: String): ChatStreamEvent? {
        return try {
            val json = jsonParser.parseToJsonElement(data).jsonObject
            val type = eventType.ifBlank {
                json["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            }
            val content = json["content"]?.jsonPrimitive?.contentOrNull ?: ""
            val metadata = json["metadata"]?.jsonObject?.toMap() ?: emptyMap()

            ChatStreamEvent(type = type, content = content, metadata = metadata)
        } catch (e: Exception) {
            logger.warn { "Failed to parse SSE data: ${data.take(200)} | error=${e.message}" }
            null
        }
    }

    /**
     * Stop an active chat session. Called when user presses Stop button.
     */
    suspend fun stopChat(sessionId: String) {
        val apiUrl = "${orchestratorBaseUrl.trimEnd('/')}/chat/stop"
        try {
            client.preparePost(apiUrl) {
                contentType(ContentType.Application.Json)
                setBody(mapOf("session_id" to sessionId))
            }.execute { response ->
                logger.info { "PYTHON_CHAT_STOP | session=$sessionId | status=${response.status}" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "PYTHON_CHAT_STOP_ERROR | session=$sessionId | error=${e.message}" }
        }
    }

    private fun JsonObject.toMap(): Map<String, Any?> {
        return entries.associate { (key, value) ->
            key to when (value) {
                is JsonPrimitive -> value.contentOrNull
                is JsonObject -> value.toMap()
                else -> value.toString()
            }
        }
    }
}

/**
 * Request body for Python /chat endpoint.
 */
@Serializable
private data class PythonChatRequest(
    @SerialName("session_id") val sessionId: String,
    val message: String,
    @SerialName("message_sequence") val messageSequence: Int,
    @SerialName("user_id") val userId: String = "jan",
    @SerialName("active_client_id") val activeClientId: String? = null,
    @SerialName("active_project_id") val activeProjectId: String? = null,
    @SerialName("context_task_id") val contextTaskId: String? = null,
)
