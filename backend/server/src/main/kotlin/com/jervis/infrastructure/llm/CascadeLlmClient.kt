package com.jervis.infrastructure.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Server-only LLM client for short, chat-style prompts.
 *
 * Contract: send `X-Capability: chat` + `X-Client-Id` (when available) +
 * the payload. Router picks model / local vs cloud / retries.
 *
 * Used for: merge project AI text resolution, voice quick KB lookup.
 * NOT for orchestrator or external callers.
 */
@Component
class CascadeLlmClient(
    @Value("\${endpoints.ollama-router.url:http://jervis-ollama-router:11430}")
    private val routerUrl: String,
) {
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = Long.MAX_VALUE   // LLM trvá jak trvá, žádný read timeout
            socketTimeoutMillis = Long.MAX_VALUE
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Send a prompt via the router. Router decides the model.
     *
     * @param prompt user content
     * @param system optional system message
     * @param clientId tenant — router resolves tier from CloudModelPolicy
     */
    suspend fun prompt(prompt: String, system: String? = null, clientId: String? = null): String? {
        val body = buildMap<String, Any?> {
            put("prompt", prompt)
            if (system != null) put("system", system)
            put("stream", false)
        }

        return try {
            val response = client.post("$routerUrl/api/generate") {
                contentType(ContentType.Application.Json)
                header("X-Capability", "chat")
                if (clientId != null) header("X-Client-Id", clientId)
                setBody(Json.encodeToString(kotlinx.serialization.serializer<Map<String, Any?>>(), body))
            }
            val text = response.bodyAsText()
            val parsed = json.parseToJsonElement(text).jsonObject
            parsed["response"]?.jsonPrimitive?.content
                ?: parsed["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: text.take(5000)
        } catch (e: Exception) {
            logger.warn(e) { "CASCADE_LLM: failed: ${e.message}" }
            null
        }
    }
}
