package com.jervis.infrastructure.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
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
 * Server-only LLM client using /api/cascade endpoint on ollama-router.
 *
 * Highest priority (CASCADE=-1), instant routing through GPU-1 → GPU-2 → OpenRouter.
 * Router decides which model to use — server never specifies a model.
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
            requestTimeoutMillis = 120_000
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Send a prompt via cascade routing. Router decides the model.
     * Returns generated text or null on failure.
     */
    suspend fun prompt(prompt: String, system: String? = null): String? {
        val body = buildMap<String, Any?> {
            put("prompt", prompt)
            if (system != null) put("system", system)
            put("stream", false)
        }

        return try {
            val response = client.post("$routerUrl/api/cascade") {
                contentType(ContentType.Application.Json)
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
