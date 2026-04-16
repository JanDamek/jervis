package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.GetTranscriptRequest
import com.jervis.contracts.server.GetTranscriptResponse
import com.jervis.contracts.server.ListMeetingsRequest
import com.jervis.contracts.server.ListMeetingsResponse
import com.jervis.contracts.server.ListUnclassifiedRequest
import com.jervis.contracts.server.ListUnclassifiedResponse
import com.jervis.contracts.server.MeetingSummary
import com.jervis.contracts.server.ServerMeetingsServiceGrpcKt
import com.jervis.contracts.server.UnclassifiedMeeting
import com.jervis.meeting.MeetingRpcImpl
import io.grpc.Status
import io.grpc.StatusException
import mu.KotlinLogging
import org.springframework.stereotype.Component

// Read-side meeting surface consumed by the orchestrator's chat tools
// and system-prompt builder. Write-side (classify / recording / attend)
// lives in sibling services migrated by their own routing files.
@Component
class ServerMeetingsGrpcImpl(
    private val meetingRpcImpl: MeetingRpcImpl,
) : ServerMeetingsServiceGrpcKt.ServerMeetingsServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun getTranscript(request: GetTranscriptRequest): GetTranscriptResponse {
        if (request.meetingId.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("meeting_id required"))
        }
        val meeting = try {
            meetingRpcImpl.getMeeting(request.meetingId)
        } catch (e: IllegalStateException) {
            throw StatusException(Status.NOT_FOUND.withDescription(e.message))
        }

        val transcript = meeting.correctedTranscriptText ?: meeting.transcriptText
        if (!transcript.isNullOrBlank()) {
            return GetTranscriptResponse.newBuilder()
                .setMeetingId(request.meetingId)
                .setTitle(meeting.title ?: "")
                .setState(meeting.state.name)
                .setTranscript(transcript)
                .setFormat("text")
                .build()
        }

        val segments = meeting.correctedTranscriptSegments.ifEmpty { meeting.transcriptSegments }
        if (segments.isEmpty()) {
            return GetTranscriptResponse.newBuilder()
                .setMeetingId(request.meetingId)
                .setTitle(meeting.title ?: "")
                .setState(meeting.state.name)
                .setError("No transcript available yet")
                .build()
        }

        val formatted = segments.joinToString("\n") { seg ->
            val ts = formatTimestamp(seg.startSec)
            val speaker = seg.speakerName ?: seg.speaker ?: ""
            val prefix = if (speaker.isNotBlank()) "$speaker: " else ""
            "[$ts] $prefix${seg.text}"
        }
        return GetTranscriptResponse.newBuilder()
            .setMeetingId(request.meetingId)
            .setTitle(meeting.title ?: "")
            .setState(meeting.state.name)
            .setTranscript(formatted)
            .setFormat("segments")
            .build()
    }

    override suspend fun listMeetings(request: ListMeetingsRequest): ListMeetingsResponse {
        val limit = if (request.limit > 0) request.limit else 20
        val state = request.state.takeIf { it.isNotBlank() }
        val meetings = try {
            meetingRpcImpl.listMeetings(request.clientId, request.projectId.takeIf { it.isNotBlank() })
        } catch (e: Exception) {
            logger.warn(e) { "MEETINGS_LIST_ERROR" }
            return ListMeetingsResponse.getDefaultInstance()
        }

        val summaries = meetings
            .let { list -> if (state != null) list.filter { it.state.name == state } else list }
            .take(limit)
            .map { m ->
                MeetingSummary.newBuilder()
                    .setId(m.id)
                    .setTitle(m.title ?: "")
                    .setState(m.state.name)
                    .setClientId(m.clientId ?: "")
                    .setProjectId(m.projectId ?: "")
                    .setStartedAtIso(m.startedAt ?: "")
                    .setDurationSeconds(m.durationSeconds?.toString() ?: "")
                    .setMeetingType(m.meetingType?.name ?: "")
                    .build()
            }

        return ListMeetingsResponse.newBuilder().addAllMeetings(summaries).build()
    }

    override suspend fun listUnclassified(request: ListUnclassifiedRequest): ListUnclassifiedResponse {
        val items = try {
            meetingRpcImpl.listUnclassifiedMeetings().map { m ->
                UnclassifiedMeeting.newBuilder()
                    .setId(m.id)
                    .setTitle(m.title ?: "")
                    .setStartedAtIso(m.startedAt.toString())
                    .setDurationSeconds(m.durationSeconds?.toString() ?: "0")
                    .build()
            }
        } catch (e: Exception) {
            logger.warn(e) { "MEETINGS_UNCLASSIFIED_ERROR" }
            emptyList()
        }
        return ListUnclassifiedResponse.newBuilder().addAllMeetings(items).build()
    }

    private fun formatTimestamp(seconds: Double): String {
        val totalSec = seconds.toLong()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
