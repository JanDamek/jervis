package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.PtzRequest
import com.jervis.contracts.server.RawJsonResponse
import com.jervis.contracts.server.ServerVisualCaptureServiceGrpcKt
import com.jervis.contracts.server.SnapshotRequest
import com.jervis.contracts.server.VisualResultRequest
import com.jervis.contracts.server.VisualResultResponse
import com.jervis.dto.meeting.HelperMessageDto
import com.jervis.dto.meeting.HelperMessageType
import com.jervis.meeting.MeetingHelperService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ServerVisualCaptureGrpcImpl(
    private val helperService: MeetingHelperService,
) : ServerVisualCaptureServiceGrpcKt.ServerVisualCaptureServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}
    private val httpClient = HttpClient(CIO)
    private val captureBaseUrl: String =
        System.getenv("VISUAL_CAPTURE_URL") ?: "http://jervis-visual-capture:8096"

    @PreDestroy
    fun shutdown() {
        runCatching { httpClient.close() }
    }

    override suspend fun postResult(request: VisualResultRequest): VisualResultResponse {
        if (request.meetingId.isNotBlank()) {
            val msgType = when (request.type) {
                "whiteboard", "whiteboard_ocr" -> HelperMessageType.WHITEBOARD_OCR
                "screen", "screen_ocr" -> HelperMessageType.SCREEN_OCR
                else -> HelperMessageType.VISUAL_INSIGHT
            }
            val text = buildString {
                if (request.description.isNotBlank()) append(request.description)
                if (request.ocrText.isNotBlank()) {
                    if (isNotEmpty()) append("\n---\n")
                    append("OCR: ").append(request.ocrText)
                }
            }.take(2000)
            helperService.pushMessage(
                request.meetingId,
                HelperMessageDto(
                    type = msgType,
                    text = text,
                    context = request.presetName,
                    timestamp = request.timestampIso.ifBlank { Instant.now().toString() },
                ),
            )
        }
        logger.info {
            "VISUAL_RESULT | meeting=${request.meetingId.ifBlank { "-" }} | type=${request.type} | " +
                "desc=${request.description.take(80)} | ocr=${request.ocrText.take(80)} | " +
                "preset=${request.presetName}"
        }
        return VisualResultResponse.newBuilder().setOk(true).build()
    }

    override suspend fun snapshot(request: SnapshotRequest): RawJsonResponse = proxy(
        path = "/capture/snapshot",
        body = request.requestJson,
        label = "VISUAL_SNAPSHOT_PROXY_ERROR",
    )

    override suspend fun ptz(request: PtzRequest): RawJsonResponse = proxy(
        path = "/ptz/goto",
        body = request.requestJson,
        label = "VISUAL_PTZ_PROXY_ERROR",
    )

    private suspend fun proxy(path: String, body: String, label: String): RawJsonResponse {
        return try {
            val resp = httpClient.post("$captureBaseUrl$path") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val bodyText = resp.bodyAsText()
            RawJsonResponse.newBuilder()
                .setBodyJson(bodyText)
                .setStatus(resp.status.value)
                .build()
        } catch (e: Exception) {
            logger.warn(e) { label }
            RawJsonResponse.newBuilder()
                .setBodyJson("""{"status":"error","error":"${e.message?.take(200)}"}""")
                .setStatus(502)
                .build()
        }
    }
}
