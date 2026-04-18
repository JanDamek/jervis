package com.jervis.meeting

import com.jervis.common.types.ConnectionId
import com.jervis.dto.events.JervisEvent
import com.jervis.infrastructure.grpc.O365BrowserPoolGrpcClient
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Dispatches a meeting join/leave instruction into an O365 browser pod via
 * O365BrowserPoolService.PushInstruction (gRPC, fire-and-forget).
 *
 * The pod's LangGraph agent picks up the HumanMessage and composes the
 * navigate + mute + click Join tool chain; this client just fans the text
 * prompt into the pod.
 */
@Component
class BrowserPodMeetingClient(
    private val o365BrowserPoolGrpc: O365BrowserPoolGrpcClient,
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
            val resp = o365BrowserPoolGrpc.pushInstruction(cid, connectionId, instruction)
            if (resp.status == "queued") {
                logger.info {
                    "BrowserPodMeetingClient: dispatched join_meeting " +
                        "task=${trigger.taskId} conn=$connectionId"
                }
                true
            } else {
                logger.warn {
                    "BrowserPodMeetingClient: pod returned status=${resp.status} " +
                        "error=${resp.error} for task=${trigger.taskId}"
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
        val instruction =
            "INSTRUCTION: leave_meeting. meeting_id=$meetingId reason=$reason. " +
                "Call leave_meeting(meeting_id='$meetingId', reason='$reason')."
        return try {
            val resp = o365BrowserPoolGrpc.pushInstruction(cid, connectionId, instruction)
            resp.status == "queued"
        } catch (e: Exception) {
            logger.warn(e) {
                "BrowserPodMeetingClient: leave dispatch failed meeting=$meetingId"
            }
            false
        }
    }
}
