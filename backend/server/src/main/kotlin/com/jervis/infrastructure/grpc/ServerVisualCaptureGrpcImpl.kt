package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.ProxyPtzRequest
import com.jervis.contracts.server.ProxyPtzResponse
import com.jervis.contracts.server.ProxySnapshotRequest
import com.jervis.contracts.server.ProxySnapshotResponse
import com.jervis.contracts.server.ServerVisualCaptureServiceGrpcKt
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

    override suspend fun snapshot(request: ProxySnapshotRequest): ProxySnapshotResponse =
        try {
            val resp = visualCaptureGrpc.snapshot(
                mode = request.mode,
                preset = request.preset,
                customPrompt = request.customPrompt,
            )
            ProxySnapshotResponse.newBuilder()
                .setDescription(resp.description)
                .setOcrText(resp.ocrText)
                .setMode(resp.mode)
                .setModel(resp.model)
                .setFrameSizeBytes(resp.frameSizeBytes)
                .setTimestamp(resp.timestamp)
                .setPreset(resp.preset)
                .setError(resp.error)
                .build()
        } catch (e: Exception) {
            logger.warn(e) { "VISUAL_SNAPSHOT_PROXY_ERROR" }
            ProxySnapshotResponse.newBuilder()
                .setError(e.message?.take(200).orEmpty())
                .build()
        }

    override suspend fun ptz(request: ProxyPtzRequest): ProxyPtzResponse =
        try {
            val resp = visualCaptureGrpc.ptzGoto(request.preset)
            ProxyPtzResponse.newBuilder()
                .setStatus(resp.status)
                .setPreset(resp.preset)
                .setError(resp.error)
                .build()
        } catch (e: Exception) {
            logger.warn(e) { "VISUAL_PTZ_PROXY_ERROR" }
            ProxyPtzResponse.newBuilder()
                .setStatus("error")
                .setError(e.message?.take(200).orEmpty())
                .build()
        }
}
