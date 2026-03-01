package com.jervis.rpc.internal

import com.jervis.rpc.OpenRouterSettingsRpcImpl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/**
 * Internal REST endpoints for OpenRouter settings, called by Python orchestrator.
 *
 * GET /internal/openrouter-settings → OpenRouterSettingsDto (with model queues)
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
}
