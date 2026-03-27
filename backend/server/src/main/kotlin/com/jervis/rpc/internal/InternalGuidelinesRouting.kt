package com.jervis.rpc.internal

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.guidelines.GuidelinesScope
import com.jervis.dto.guidelines.GuidelinesUpdateRequest
import com.jervis.guidelines.GuidelinesService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/**
 * Internal REST endpoints for guidelines, called by Python orchestrator.
 *
 * GET  /internal/guidelines/merged?clientId=...&projectId=...  → MergedGuidelinesDto
 * GET  /internal/guidelines?scope=...&clientId=...&projectId=... → GuidelinesDocumentDto
 * POST /internal/guidelines → update guidelines (GuidelinesUpdateRequest)
 */
fun Routing.installInternalGuidelinesApi(
    guidelinesService: GuidelinesService,
) {
    get("/internal/guidelines/merged") {
        try {
            val clientId = call.parameters["clientId"]?.let { ClientId.fromString(it) }
            val projectId = call.parameters["projectId"]?.let { ProjectId.fromString(it) }
            val merged = guidelinesService.getMergedGuidelines(clientId, projectId)
            call.respondText(json.encodeToString(merged), ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=guidelines/merged" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    get("/internal/guidelines") {
        try {
            val scope = GuidelinesScope.valueOf(
                call.parameters["scope"] ?: "GLOBAL",
            )
            val clientId = call.parameters["clientId"]?.let { ClientId.fromString(it) }
            val projectId = call.parameters["projectId"]?.let { ProjectId.fromString(it) }
            val doc = guidelinesService.getGuidelines(scope, clientId, projectId)
            call.respondText(json.encodeToString(doc.toDto()), ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=guidelines" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    post("/internal/guidelines") {
        try {
            val request = json.decodeFromString<GuidelinesUpdateRequest>(call.receive<String>())
            val doc = guidelinesService.updateGuidelines(request)
            call.respondText(json.encodeToString(doc.toDto()), ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=guidelines (POST)" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}
