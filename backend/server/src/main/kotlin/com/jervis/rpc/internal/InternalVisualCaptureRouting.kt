package com.jervis.rpc.internal

import com.jervis.dto.meeting.HelperMessageDto
import com.jervis.dto.meeting.HelperMessageType
import com.jervis.meeting.MeetingHelperService
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Internal HTTP bridge for the `service-visual-capture` K8s pod.
 *
 * Receives VLM analysis results from the camera capture pod, routes them
 * through [MeetingHelperService] for real-time UI push (if linked to a
 * meeting), and stores visual context in KB. Also proxies on-demand
 * snapshot + PTZ commands to the capture pod.
 */
fun Routing.installInternalVisualCaptureApi(
    helperService: MeetingHelperService,
) {
    val httpClient = HttpClient(io.ktor.client.engine.cio.CIO)
    val captureBaseUrl = System.getenv("VISUAL_CAPTURE_URL") ?: "http://jervis-visual-capture:8096"

    // ── Receive VLM analysis result from capture pod ─────────────────

    post("/internal/visual-capture/result") {
        try {
            val body = call.receive<VisualCaptureResult>()

            // If linked to a meeting, push through MeetingHelper stream
            if (!body.meetingId.isNullOrBlank()) {
                val msgType = when (body.type) {
                    "whiteboard", "whiteboard_ocr" -> HelperMessageType.WHITEBOARD_OCR
                    "screen", "screen_ocr" -> HelperMessageType.SCREEN_OCR
                    else -> HelperMessageType.VISUAL_INSIGHT
                }
                val text = buildString {
                    if (body.description.isNotBlank()) append(body.description)
                    if (body.ocrText.isNotBlank()) {
                        if (isNotEmpty()) append("\n---\n")
                        append("OCR: ").append(body.ocrText)
                    }
                }.take(2000)  // cap message size

                helperService.pushMessage(
                    body.meetingId!!,
                    HelperMessageDto(
                        type = msgType,
                        text = text,
                        context = body.presetName ?: "",
                        timestamp = body.timestamp ?: Instant.now().toString(),
                    ),
                )
            }

            logger.info {
                "VISUAL_RESULT | meeting=${body.meetingId ?: "-"} | type=${body.type} | " +
                    "desc=${body.description.take(80)} | ocr=${body.ocrText.take(80)} | " +
                    "preset=${body.presetName}"
            }
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "VISUAL_RESULT_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // ── Proxy: on-demand snapshot (Kotlin server → capture pod) ──────

    post("/internal/visual-capture/snapshot") {
        try {
            val requestBody = call.receive<String>()
            val resp = httpClient.post("$captureBaseUrl/capture/snapshot") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            call.respondText(resp.bodyAsText(), ContentType.Application.Json, resp.status)
        } catch (e: Exception) {
            logger.warn(e) { "VISUAL_SNAPSHOT_PROXY_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadGateway,
            )
        }
    }

    // ── Proxy: PTZ control (Kotlin server → capture pod) ────────────

    post("/internal/visual-capture/ptz") {
        try {
            val requestBody = call.receive<String>()
            val resp = httpClient.post("$captureBaseUrl/ptz/goto") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            call.respondText(resp.bodyAsText(), ContentType.Application.Json, resp.status)
        } catch (e: Exception) {
            logger.warn(e) { "VISUAL_PTZ_PROXY_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadGateway,
            )
        }
    }
}

@Serializable
private data class VisualCaptureResult(
    val meetingId: String? = null,
    val type: String = "scene",
    val description: String = "",
    val ocrText: String = "",
    val presetName: String? = null,
    val timestamp: String? = null,
    val model: String? = null,
)
