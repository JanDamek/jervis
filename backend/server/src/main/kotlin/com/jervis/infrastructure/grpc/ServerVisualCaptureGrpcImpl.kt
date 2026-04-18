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
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ServerVisualCaptureGrpcImpl(
    private val helperService: MeetingHelperService,
    private val visualCaptureGrpc: VisualCaptureGrpcClient,
) : ServerVisualCaptureServiceGrpcKt.ServerVisualCaptureServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

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

    override suspend fun snapshot(request: SnapshotRequest): RawJsonResponse =
        try {
            val resp = visualCaptureGrpc.snapshot(request.requestJson)
            RawJsonResponse.newBuilder()
                .setBodyJson(resp.bodyJson)
                .setStatus(resp.status)
                .build()
        } catch (e: Exception) {
            logger.warn(e) { "VISUAL_SNAPSHOT_PROXY_ERROR" }
            RawJsonResponse.newBuilder()
                .setBodyJson("""{"status":"error","error":"${e.message?.take(200)}"}""")
                .setStatus(502)
                .build()
        }

    override suspend fun ptz(request: PtzRequest): RawJsonResponse =
        try {
            val resp = visualCaptureGrpc.ptzGoto(request.requestJson)
            RawJsonResponse.newBuilder()
                .setBodyJson(resp.bodyJson)
                .setStatus(resp.status)
                .build()
        } catch (e: Exception) {
            logger.warn(e) { "VISUAL_PTZ_PROXY_ERROR" }
            RawJsonResponse.newBuilder()
                .setBodyJson("""{"status":"error","error":"${e.message?.take(200)}"}""")
                .setStatus(502)
                .build()
        }
}
