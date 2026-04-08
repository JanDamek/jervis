package com.jervis.meeting

import com.jervis.dto.events.JervisEvent
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * HTTP client for the K8s `service-meeting-attender` pod (Etapa 2B).
 *
 * Used as the fallback path by `MeetingRecordingDispatcher` when the
 * approved client has no live desktop event subscriber. The pod's REST API
 * is intentionally minimal — `attend` to start a session, `stop` to cancel
 * one early. The pod owns no persistent state; if it crashes mid-session,
 * the dispatcher's `recordingDispatchedAt` lock would normally prevent
 * re-dispatch on next cycle, but the dispatcher resets the lock on
 * `attend()` HTTP failure so a healthy retry can land the session.
 *
 * Read-only v1: this client never sends anything except an attend / stop
 * request. The pod itself enforces "no chat / no mic / no auto-join"
 * invariants on the meeting tab.
 */
@Component
class MeetingAttenderClient(
    private val httpClient: HttpClient,
    @Value("\${jervis.meeting-attender.url:http://jervis-meeting-attender:8095}")
    private val attenderUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Open a new attend session on the pod for an approved meeting trigger.
     * Returns `true` if the pod accepted the request, `false` on any error
     * (network, 4xx/5xx, JSON parse). The dispatcher uses the boolean to
     * decide whether to keep the `recordingDispatchedAt` lock set.
     */
    suspend fun attend(trigger: JervisEvent.MeetingRecordingTrigger): Boolean {
        return try {
            val payload = AttenderAttendRequest(
                task_id = trigger.taskId,
                client_id = trigger.clientId,
                project_id = trigger.projectId,
                title = trigger.title,
                join_url = trigger.joinUrl ?: "",
                end_time_iso = trigger.endTime,
                provider = trigger.provider,
            )
            val response = httpClient.post("$attenderUrl/attend") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(AttenderAttendRequest.serializer(), payload))
            }
            if (response.status.isSuccess()) {
                logger.info { "MeetingAttenderClient: pod accepted attend for task=${trigger.taskId}" }
                true
            } else {
                val body = response.bodyAsText()
                logger.warn { "MeetingAttenderClient: pod returned ${response.status} for task=${trigger.taskId}: $body" }
                false
            }
        } catch (e: Exception) {
            logger.warn(e) { "MeetingAttenderClient: failed to call attender pod for task=${trigger.taskId}" }
            false
        }
    }

    /**
     * Tell the pod to stop a session early — used when the user denies
     * after-approve or cancels the meeting from the calendar.
     */
    suspend fun stop(taskId: String, reason: String): Boolean {
        return try {
            val response = httpClient.post("$attenderUrl/stop") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(AttenderStopRequest.serializer(), AttenderStopRequest(task_id = taskId, reason = reason)))
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.warn(e) { "MeetingAttenderClient: failed to stop session task=$taskId" }
            false
        }
    }

    @Serializable
    private data class AttenderAttendRequest(
        val task_id: String,
        val client_id: String,
        val project_id: String? = null,
        val title: String,
        val join_url: String,
        val end_time_iso: String,
        val provider: String,
    )

    @Serializable
    private data class AttenderStopRequest(
        val task_id: String,
        val reason: String,
    )
}
