package com.jervis.rpc.internal

import com.jervis.dto.meeting.AudioChunkDto
import com.jervis.dto.meeting.MeetingCreateDto
import com.jervis.dto.meeting.MeetingFinalizeDto
import com.jervis.dto.meeting.MeetingTypeEnum
import com.jervis.meeting.MeetingRpcImpl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * HTTP bridge for the K8s `service-meeting-attender` pod.
 *
 * The attender pod is a Python process that captures audio for an approved
 * meeting on behalf of the user (Etapa 2B). It cannot speak kRPC/CBOR, so we
 * expose three thin HTTP shims that delegate directly to [MeetingRpcImpl] —
 * the same in-process service the desktop calls over kRPC. There is no logic
 * difference between the two paths; this routing exists ONLY because the
 * Python pod can't link against the Kotlin RPC stubs.
 *
 * Endpoints:
 *
 * - `POST /internal/meeting/start-recording` — body: [BridgeStartRequest],
 *   returns the new `MeetingDocument.id` so the pod can attach chunks.
 * - `POST /internal/meeting/upload-chunk`    — body: [BridgeUploadRequest],
 *   appends a base64 PCM frame to the WAV file. Returns the chunk count.
 * - `POST /internal/meeting/finalize-recording` — body: [BridgeFinalizeRequest],
 *   fixes the WAV header and transitions the meeting to UPLOADED so the
 *   transcription pipeline picks it up.
 *
 * Read-only v1 invariants are inherited from `MeetingRpcImpl` itself — this
 * routing adds no new write paths and never sends messages anywhere.
 */
fun Routing.installInternalMeetingRecordingBridgeApi(
    meetingRpcImpl: MeetingRpcImpl,
) {
    post("/internal/meeting/start-recording") {
        try {
            val body = call.receive<BridgeStartRequest>()
            val dto = MeetingCreateDto(
                clientId = body.clientId,
                projectId = body.projectId,
                title = body.title,
                meetingType = body.meetingType?.let { runCatching { MeetingTypeEnum.valueOf(it) }.getOrNull() }
                    ?: MeetingTypeEnum.MEETING,
                deviceSessionId = body.deviceSessionId,
            )
            val meeting = meetingRpcImpl.startRecording(dto)
            // If the attender pod (or any other bridge caller) tells us the
            // source CALENDAR_PROCESSING task, persist the cross-link the
            // same way the desktop loopback recorder does.
            body.taskId?.takeIf { it.isNotBlank() }?.let { taskId ->
                runCatching { meetingRpcImpl.linkMeetingToTask(taskId, meeting.id) }
            }
            call.respondText(
                buildJsonObject {
                    put("id", meeting.id)
                    put("state", meeting.state.name)
                }.toString(),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=meeting/start-recording" }
            call.respondText(
                """{"error":"${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    post("/internal/meeting/upload-chunk") {
        try {
            val body = call.receive<BridgeUploadRequest>()
            val count = meetingRpcImpl.uploadAudioChunk(
                AudioChunkDto(
                    meetingId = body.meetingId,
                    chunkIndex = body.chunkIndex,
                    data = body.data,
                    mimeType = body.mimeType,
                    isLast = body.isLast,
                ),
            )
            call.respondText(
                buildJsonObject {
                    put("meetingId", body.meetingId)
                    put("chunkCount", count)
                }.toString(),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=meeting/upload-chunk" }
            call.respondText(
                """{"error":"${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    post("/internal/meeting/finalize-recording") {
        try {
            val body = call.receive<BridgeFinalizeRequest>()
            val meetingType = runCatching { MeetingTypeEnum.valueOf(body.meetingType) }
                .getOrDefault(MeetingTypeEnum.MEETING)
            val meeting = meetingRpcImpl.finalizeRecording(
                MeetingFinalizeDto(
                    meetingId = body.meetingId,
                    title = body.title,
                    meetingType = meetingType,
                    durationSeconds = body.durationSeconds,
                ),
            )
            call.respondText(
                buildJsonObject {
                    put("id", meeting.id)
                    put("state", meeting.state.name)
                    put("durationSeconds", meeting.durationSeconds ?: 0L)
                }.toString(),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=meeting/finalize-recording" }
            call.respondText(
                """{"error":"${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

@Serializable
private data class BridgeStartRequest(
    val clientId: String? = null,
    val projectId: String? = null,
    val title: String? = null,
    val meetingType: String? = null,
    val deviceSessionId: String? = null,
    /** Optional source CALENDAR_PROCESSING task id; sets meetingMetadata.recordingMeetingId. */
    val taskId: String? = null,
    /** Who initiated the recording — 'user' (VNC click) or 'agent' (/instruction/). */
    val joinedBy: String? = null,
)

@Serializable
private data class BridgeUploadRequest(
    val meetingId: String,
    val chunkIndex: Int,
    val data: String,
    val mimeType: String = "audio/pcm",
    val isLast: Boolean = false,
)

@Serializable
private data class BridgeFinalizeRequest(
    val meetingId: String,
    val meetingType: String,
    val durationSeconds: Long,
    val title: String? = null,
    val stopReason: String? = null,
)
