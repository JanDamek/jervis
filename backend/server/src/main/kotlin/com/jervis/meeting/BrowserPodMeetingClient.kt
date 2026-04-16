package com.jervis.meeting

import com.jervis.common.types.ConnectionId
import com.jervis.connection.BrowserPodManager
import com.jervis.dto.events.JervisEvent
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Dispatches a meeting join instruction into an O365 browser pod.
 *
 * When `MeetingRecordingDispatcher` has a calendar-driven TEAMS meeting
 * whose `MeetingMetadata.connectionId` is known, this client POSTs an
 * `INSTRUCTION: join_meeting` payload to the matching pod's
 * `/instruction/{connectionId}` endpoint. The pod's LangGraph agent
 * picks up the HumanMessage and composes navigate + mute + click Join
 * via tools, then calls `start_meeting_recording(joined_by='agent')`.
 *
 * The endpoint is fire-and-forget — the pod answers 202/queued and runs
 * the tool chain asynchronously.
 */
@Component
class BrowserPodMeetingClient(
    private val httpClient: HttpClient,
) {
    suspend fun dispatchJoin(
        connectionId: String,
        trigger: JervisEvent.MeetingRecordingTrigger,
    ): Boolean {
        val cid = try {
            ConnectionId(ObjectId(connectionId))
        } catch (_: Exception) {
            logger.warn { "BrowserPodMeetingClient: invalid connectionId=$connectionId" }
            return false
        }
        val podUrl = BrowserPodManager.serviceUrl(cid)
        val instruction = buildString {
            append("INSTRUCTION: join_meeting. ")
            append("meeting_id=${trigger.taskId}. ")
            append("title=${trigger.title}. ")
            append("join_url=${trigger.joinUrl ?: ""}. ")
            append("start_time=${trigger.startTime}. ")
            append("end_time=${trigger.endTime}. ")
            append(
                "Compose: open_tab(url=join_url, name='meeting'); " +
                    "look_at_screen(reason='teams_prejoin'); " +
                    "mute mic + camera; click('[data-tid=\"prejoin-join-button\"]') " +
                    "or click_visual('Join now'); poll " +
                    "inspect_dom('[data-tid=\"meeting-stage\"]') until visible; " +
                    "start_meeting_recording(meeting_id='${trigger.taskId}', " +
                    "joined_by='agent', tab_name='meeting') + " +
                    "meeting_presence_report(present=true, meeting_stage_visible=true)."
            )
        }
        return try {
            val resp = httpClient.post("$podUrl/instruction/$connectionId") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("instruction", instruction) }.toString())
            }
            if (resp.status.isSuccess()) {
                logger.info {
                    "BrowserPodMeetingClient: dispatched join_meeting " +
                        "task=${trigger.taskId} conn=$connectionId"
                }
                true
            } else {
                val bodySnippet = resp.bodyAsText().take(200)
                logger.warn {
                    "BrowserPodMeetingClient: pod returned ${resp.status} " +
                        "for task=${trigger.taskId}: $bodySnippet"
                }
                false
            }
        } catch (e: Exception) {
            logger.warn(e) {
                "BrowserPodMeetingClient: dispatch failed task=${trigger.taskId}"
            }
            false
        }
    }

    suspend fun dispatchLeave(
        connectionId: String,
        meetingId: String,
        reason: String,
    ): Boolean {
        val cid = try {
            ConnectionId(ObjectId(connectionId))
        } catch (_: Exception) {
            return false
        }
        val podUrl = BrowserPodManager.serviceUrl(cid)
        val instruction =
            "INSTRUCTION: leave_meeting. meeting_id=$meetingId reason=$reason. " +
                "Call leave_meeting(meeting_id='$meetingId', reason='$reason')."
        return try {
            val resp = httpClient.post("$podUrl/instruction/$connectionId") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("instruction", instruction) }.toString())
            }
            resp.status.isSuccess()
        } catch (e: Exception) {
            logger.warn(e) {
                "BrowserPodMeetingClient: leave dispatch failed meeting=$meetingId"
            }
            false
        }
    }
}
