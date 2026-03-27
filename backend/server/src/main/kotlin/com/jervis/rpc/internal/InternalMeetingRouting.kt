package com.jervis.rpc.internal

import com.jervis.meeting.MeetingRpcImpl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Internal REST endpoints for meeting data access.
 *
 * Provides meeting details and transcript retrieval for the Python orchestrator.
 * Called from kotlin_client in the orchestrator chat/background agents.
 */
fun Routing.installInternalMeetingApi(
    meetingRpcImpl: MeetingRpcImpl,
) {
    // Get meeting transcript — corrected preferred, fallback raw
    get("/internal/meetings/{id}/transcript") {
        try {
            val meetingId = call.parameters["id"] ?: run {
                call.respondText("""{"error":"Missing meeting ID"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@get
            }
            val meeting = meetingRpcImpl.getMeeting(meetingId)
            val transcript = meeting.correctedTranscriptText ?: meeting.transcriptText

            if (transcript.isNullOrBlank()) {
                // Fall back to segments
                val segments = meeting.correctedTranscriptSegments.ifEmpty { meeting.transcriptSegments }
                if (segments.isNotEmpty()) {
                    val formatted = segments.joinToString("\n") { seg ->
                        val ts = formatTimestamp(seg.startSec)
                        val speaker = seg.speakerName ?: seg.speaker ?: ""
                        val prefix = if (speaker.isNotBlank()) "$speaker: " else ""
                        "[$ts] $prefix${seg.text}"
                    }
                    val result = buildJsonObject {
                        put("meetingId", meetingId)
                        put("title", meeting.title ?: "")
                        put("state", meeting.state.name)
                        put("transcript", formatted)
                        put("format", "segments")
                    }
                    call.respondText(result.toString(), ContentType.Application.Json)
                } else {
                    call.respondText(
                        """{"meetingId":"$meetingId","title":"${meeting.title ?: ""}","state":"${meeting.state.name}","transcript":"","error":"No transcript available yet"}""",
                        ContentType.Application.Json,
                    )
                }
            } else {
                val result = buildJsonObject {
                    put("meetingId", meetingId)
                    put("title", meeting.title ?: "")
                    put("state", meeting.state.name)
                    put("transcript", transcript)
                    put("format", "text")
                }
                call.respondText(result.toString(), ContentType.Application.Json)
            }
        } catch (e: IllegalStateException) {
            call.respondText("""{"error":"${e.message}"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=meetings/transcript" }
            call.respondText("""{"error":"${e.message}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
        }
    }

    // List meetings — optional filters by client, project, state
    get("/internal/meetings") {
        try {
            val clientId = call.request.queryParameters["client_id"] ?: ""
            val projectId = call.request.queryParameters["project_id"]
            val stateFilter = call.request.queryParameters["state"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20

            val meetings = meetingRpcImpl.listMeetings(clientId, projectId)
            val filtered = meetings
                .let { list -> if (stateFilter != null) list.filter { it.state.name == stateFilter } else list }
                .take(limit)

            val result = buildJsonArray {
                for (m in filtered) {
                    add(buildJsonObject {
                        put("id", m.id)
                        put("title", m.title ?: "")
                        put("state", m.state.name)
                        put("clientId", m.clientId ?: "")
                        put("projectId", m.projectId ?: "")
                        put("startedAt", m.startedAt ?: "")
                        put("durationSeconds", m.durationSeconds?.toString() ?: "")
                        put("meetingType", m.meetingType?.name ?: "")
                    })
                }
            }
            call.respondText(result.toString(), ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=meetings/list" }
            call.respondText("[]", ContentType.Application.Json)
        }
    }
}

private fun formatTimestamp(seconds: Double): String {
    val totalSec = seconds.toLong()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
