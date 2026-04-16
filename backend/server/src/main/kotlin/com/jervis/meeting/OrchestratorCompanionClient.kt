package com.jervis.meeting

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

// HTTP client for Python orchestrator companion endpoints.
// Companion is a parallel K8s Job agent used for deep analyses and live
// meeting assistant. This client starts a persistent companion session,
// forwards meeting transcript events, and tails outbox SSE.
@Component
class OrchestratorCompanionClient(
    @Value("\${endpoints.orchestrator.baseUrl:http://localhost:8090}") private val orchestratorBaseUrl: String,
) {
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonParser)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = Long.MAX_VALUE
        }
    }

    @Serializable
    data class SessionStartRequest(
        val session_id: String? = null,
        val brief: String,
        val client_id: String = "",
        val project_id: String? = null,
        val language: String = "cs",
        val context: Map<String, String> = emptyMap(),
        val attachment_paths: List<String> = emptyList(),
    )

    @Serializable
    data class SessionStartResponse(
        val job_name: String,
        val workspace_path: String,
        val session_id: String,
    )

    @Serializable
    data class SessionEventRequest(
        val type: String,
        val content: String,
        val meta: Map<String, String> = emptyMap(),
    )

    @Serializable
    data class OutboxEvent(
        val ts: String? = null,
        val type: String,
        val content: String,
        val final: Boolean? = null,
    )

    suspend fun startSession(req: SessionStartRequest): SessionStartResponse {
        val resp = client.post("$orchestratorBaseUrl/companion/session") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        if (resp.status != HttpStatusCode.OK) {
            throw IllegalStateException("Companion startSession HTTP ${resp.status.value}: ${resp.body<String>()}")
        }
        return resp.body()
    }

    suspend fun sendEvent(sessionId: String, req: SessionEventRequest) {
        val resp = client.post("$orchestratorBaseUrl/companion/session/$sessionId/event") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        if (resp.status != HttpStatusCode.OK) {
            logger.warn { "Companion sendEvent HTTP ${resp.status.value} for session=$sessionId" }
        }
    }

    suspend fun stopSession(sessionId: String) {
        runCatching {
            client.post("$orchestratorBaseUrl/companion/session/$sessionId/stop")
        }.onFailure { e ->
            logger.warn(e) { "Companion stopSession failed: $sessionId" }
        }
    }

    // Tail outbox SSE stream. Each emitted event is a parsed OutboxEvent.
    fun streamOutbox(sessionId: String, maxAgeSeconds: Int? = null): Flow<OutboxEvent> = flow {
        val url = buildString {
            append("$orchestratorBaseUrl/companion/session/$sessionId/stream")
            if (maxAgeSeconds != null) append("?max_age_seconds=$maxAgeSeconds")
        }
        val resp: HttpResponse = client.get(url)
        val channel = resp.bodyAsChannel()
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.substring(5).trim()
            if (payload.isEmpty()) continue
            val parsed = runCatching { jsonParser.decodeFromString<OutboxEvent>(payload) }
                .getOrElse {
                    logger.debug { "Bad SSE payload: $payload" }
                    null
                } ?: continue
            emit(parsed)
        }
    }
}
