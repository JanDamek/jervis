package com.jervis.rpc.internal

import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.infrastructure.storage.DirectoryStructureService
import com.jervis.meeting.MeetingRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging
import org.bson.types.ObjectId

private val logger = KotlinLogging.logger {}

/**
 * Pod-recorded meeting WebM pipeline (product §10a).
 *
 * - POST /internal/meeting/{id}/video-chunk?chunkIndex=N
 *     body: raw video/webm bytes
 *     Saves the chunk to the per-meeting chunk dir. Idempotent: duplicate
 *     chunkIndex returns 200 with `deduped=true` and does not re-append.
 *     Updates MeetingDocument.chunksReceived + lastChunkAt + state
 *     transitions from RECORDING → UPLOADED behavior stays on the other
 *     bridge endpoints.
 *
 * - POST /internal/meeting/{id}/finalize
 *     body: { chunksUploaded, joinedBy }
 *     Marks the recording complete (transition to UPLOADED so the
 *     continuous indexer picks it up; the meeting video indexer will
 *     concat chunks + run VLM frame descriptions separately).
 */
fun Routing.installInternalMeetingVideoApi(
    meetingRepository: MeetingRepository,
    directoryStructureService: DirectoryStructureService,
) {
    post("/internal/meeting/{meetingId}/video-chunk") {
        val raw = call.parameters["meetingId"].orEmpty()
        val meetingId = try {
            ObjectId(raw)
        } catch (_: Exception) {
            call.respondText(
                """{"error":"invalid meetingId"}""",
                ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            return@post
        }
        val chunkIndex = call.request.queryParameters["chunkIndex"]?.toIntOrNull() ?: run {
            call.respondText(
                """{"error":"missing chunkIndex"}""",
                ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            return@post
        }

        val meeting = meetingRepository.findById(meetingId) ?: run {
            call.respondText(
                """{"error":"meeting not found"}""",
                ContentType.Application.Json, HttpStatusCode.NotFound,
            )
            return@post
        }

        val chunkDir = directoryStructureService.meetingVideoChunkDir(
            meetingId, meeting.clientId, meeting.projectId,
        )
        val chunkPath = chunkDir.resolve("chunk_%06d.webm".format(chunkIndex))

        // Idempotency: if the chunk already exists with non-zero size, skip.
        if (Files.exists(chunkPath) && Files.size(chunkPath) > 0) {
            call.respondText(
                buildJsonObject {
                    put("meetingId", raw)
                    put("chunkIndex", chunkIndex)
                    put("deduped", true)
                    put("chunksReceived", meeting.chunksReceived)
                }.toString(),
                ContentType.Application.Json,
            )
            return@post
        }

        try {
            val bytes = call.receiveChannel().readRemaining().readByteArray()
            if (bytes.isEmpty()) {
                call.respondText(
                    """{"error":"empty body"}""",
                    ContentType.Application.Json, HttpStatusCode.BadRequest,
                )
                return@post
            }
            Files.write(
                chunkPath, bytes,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            )
            val now = Instant.now()
            val updated = meeting.copy(
                chunksReceived = meeting.chunksReceived + 1,
                lastChunkAt = now,
                // Mirror chunkCount for UI parity with audio meetings.
                chunkCount = meeting.chunkCount + 1,
            )
            meetingRepository.save(updated)
            call.respondText(
                buildJsonObject {
                    put("meetingId", raw)
                    put("chunkIndex", chunkIndex)
                    put("chunksReceived", updated.chunksReceived)
                    put("bytes", bytes.size)
                }.toString(),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "video-chunk write failed meeting=$raw idx=$chunkIndex" }
            call.respondText(
                """{"error":"${e.message}"}""",
                ContentType.Application.Json, HttpStatusCode.InternalServerError,
            )
        }
    }

    post("/internal/meeting/{meetingId}/finalize") {
        val raw = call.parameters["meetingId"].orEmpty()
        val meetingId = try {
            ObjectId(raw)
        } catch (_: Exception) {
            call.respondText(
                """{"error":"invalid meetingId"}""",
                ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            return@post
        }
        val meeting = meetingRepository.findById(meetingId) ?: run {
            call.respondText(
                """{"error":"meeting not found"}""",
                ContentType.Application.Json, HttpStatusCode.NotFound,
            )
            return@post
        }

        val body = try {
            call.receive<MeetingVideoFinalizeRequest>()
        } catch (_: Exception) {
            MeetingVideoFinalizeRequest()
        }

        val chunkDir = directoryStructureService.meetingVideoChunkDir(
            meetingId, meeting.clientId, meeting.projectId,
        )
        val webmPath = directoryStructureService
            .meetingVideoFile(meetingId, meeting.clientId, meeting.projectId)
            .toString()

        // Retention — 365 days by default. Indexer keeps metadata + transcript
        // indefinitely; nightly cleanup drops only the WebM after this cutoff.
        val retentionUntil = Instant.now().plus(Duration.ofDays(365))

        val updated = meeting.copy(
            state = MeetingStateEnum.UPLOADED,
            stoppedAt = Instant.now(),
            stateChangedAt = Instant.now(),
            joinedByAgent = (body.joinedBy ?: "").equals("agent", ignoreCase = true),
            webmPath = webmPath,
            videoRetentionUntil = retentionUntil,
        )
        meetingRepository.save(updated)

        logger.info {
            "MEETING_VIDEO_FINALIZED | meeting=$raw chunks=${updated.chunksReceived} " +
            "dir=$chunkDir webm=$webmPath joinedByAgent=${updated.joinedByAgent}"
        }

        call.respondText(
            buildJsonObject {
                put("meetingId", raw)
                put("state", updated.state.name)
                put("chunksReceived", updated.chunksReceived)
                put("webmPath", webmPath)
                put("retentionUntil", retentionUntil.toString())
            }.toString(),
            ContentType.Application.Json,
        )
    }
}

@Serializable
private data class MeetingVideoFinalizeRequest(
    val chunksUploaded: Int? = null,
    val joinedBy: String? = null,
)
