package com.jervis.rpc.internal

import com.jervis.infrastructure.llm.ModelCallStats
import com.jervis.infrastructure.llm.OpenRouterSettingsDocument
import com.jervis.rpc.OpenRouterSettingsRpcImpl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/**
 * Internal REST endpoints for OpenRouter settings, called by Python router/orchestrator.
 *
 * GET  /internal/openrouter-settings → OpenRouterSettingsDto (with model queues + stats)
 * POST /internal/openrouter-model-stats → persist model usage stats from router
 */
fun Routing.installInternalOpenRouterApi(
    openRouterSettingsRpc: OpenRouterSettingsRpcImpl,
) {
    get("/internal/openrouter-settings") {
        try {
            val settings = openRouterSettingsRpc.getSettings()
            call.respondText(json.encodeToString(settings), ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=openrouter-settings" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    /**
     * Persist model usage stats from router into MongoDB queue entries.
     *
     * Input: {"model_id": {"call_count": N, "total_time_s": F, "total_input_tokens": N, ...}, ...}
     * Router calls this periodically (every 5 min) to persist in-memory stats.
     */
    post("/internal/openrouter-model-stats") {
        try {
            val body = json.parseToJsonElement(call.receiveText()).jsonObject
            openRouterSettingsRpc.persistModelStats(body)
            call.respondText("""{"ok":true,"models":${body.size}}""", ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=openrouter-model-stats" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}
