package com.jervis.meeting

import com.jervis.dto.meeting.AudioChunkDto
import com.jervis.dto.meeting.AudioInputType
import com.jervis.dto.meeting.MeetingCreateDto
import com.jervis.dto.meeting.MeetingFinalizeDto
import com.jervis.dto.meeting.MeetingTypeEnum
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * REST endpoints for watch ad-hoc recording.
 * iPhone receives audio from watch via WatchConnectivity, uploads here.
 *
 * POST /api/v1/meeting/start   — create meeting, returns meetingId
 * POST /api/v1/meeting/chunk   — upload audio chunk (base64)
 * POST /api/v1/meeting/stop    — finalize meeting, trigger transcription
 */
fun Routing.installWatchMeetingApi(
    meetingRpcImpl: MeetingRpcImpl,
) {
    post("/api/v1/meeting/start") {
        try {
            val body = call.receive<WatchMeetingStartRequest>()
            logger.info { "WATCH_MEETING_START | title=${body.title}" }

            val meeting = meetingRpcImpl.startRecording(
                MeetingCreateDto(
                    clientId = body.clientId,
                    projectId = body.projectId,
                    audioInputType = AudioInputType.MIXED,
                    title = body.title ?: "Watch nahravka",
                    meetingType = MeetingTypeEnum.AD_HOC,
                    deviceSessionId = body.deviceSessionId,
                )
            )

            call.respondText(
                Json.encodeToString(WatchMeetingStartResponse.serializer(), WatchMeetingStartResponse(
                    meetingId = meeting.id,
                    status = "ok",
                )),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "WATCH_MEETING_START_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    post("/api/v1/meeting/chunk") {
        try {
            val body = call.receive<WatchMeetingChunkRequest>()

            meetingRpcImpl.uploadAudioChunk(
                AudioChunkDto(
                    meetingId = body.meetingId,
                    chunkIndex = body.chunkIndex,
                    data = body.audioBase64,
                    mimeType = "audio/wav",
                    isLast = body.isLast,
                )
            )

            call.respondText(
                """{"status":"ok"}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "WATCH_MEETING_CHUNK_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    post("/api/v1/meeting/stop") {
        try {
            val body = call.receive<WatchMeetingStopRequest>()
            logger.info { "WATCH_MEETING_STOP | meetingId=${body.meetingId} | duration=${body.durationSeconds}" }

            meetingRpcImpl.finalizeRecording(
                MeetingFinalizeDto(
                    meetingId = body.meetingId,
                    title = body.title,
                    meetingType = MeetingTypeEnum.AD_HOC,
                    durationSeconds = body.durationSeconds,
                )
            )

            call.respondText(
                """{"status":"ok"}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "WATCH_MEETING_STOP_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

@Serializable
data class WatchMeetingStartRequest(
    val title: String? = null,
    val clientId: String? = null,
    val projectId: String? = null,
    val deviceSessionId: String? = null,
)

@Serializable
data class WatchMeetingStartResponse(
    val meetingId: String,
    val status: String,
)

@Serializable
data class WatchMeetingChunkRequest(
    val meetingId: String,
    val chunkIndex: Int,
    val audioBase64: String,
    val isLast: Boolean = false,
)

@Serializable
data class WatchMeetingStopRequest(
    val meetingId: String,
    val title: String? = null,
    val durationSeconds: Long,
)
