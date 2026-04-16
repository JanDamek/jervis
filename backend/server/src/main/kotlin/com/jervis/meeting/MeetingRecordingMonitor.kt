package com.jervis.meeting

import com.jervis.common.types.SourceUrn
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.task.TaskDocument
import com.jervis.task.TaskRepository
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * MeetingRecordingMonitor — product §10a stuck detector + hard ceiling.
 *
 * Runs every 60 s. Two checks per in-progress recording:
 *
 * 1. **Stuck detector** — `state=RECORDING` AND
 *    `now - lastChunkAt > 5 min` ⇒ emit a priority-90 USER_TASK
 *    ("Meeting upload stuck — N chunks pending since <lastChunkAt>").
 *    One emission per meeting per stuck window.
 *
 * 2. **Hard ceiling** — `state=RECORDING` AND a linked
 *    CALENDAR_PROCESSING task carries `meetingMetadata.endTime`, AND
 *    `now > endTime + 30 min` ⇒ dispatch `leave_meeting` to the pod via
 *    BrowserPodMeetingClient. If the meeting is still RECORDING 5 min
 *    later, emit `notify_user(kind='error')` to the user ("Jervis uvízl
 *    v meetingu, opusť manuálně").
 */
@Component
class MeetingRecordingMonitor(
    private val meetingRepository: MeetingRepository,
    private val taskRepository: TaskRepository,
    private val notificationRpc: NotificationRpcImpl,
    private val browserPodMeetingClient: BrowserPodMeetingClient,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // meeting_id → last stuck-alert timestamp (to avoid spam)
    private val stuckAlerted: MutableMap<String, Instant> = mutableMapOf()
    // meeting_id → leave_meeting dispatch timestamp (for the 5 min grace)
    private val leaveDispatchedAt: MutableMap<String, Instant> = mutableMapOf()
    // meeting_id → error-notified timestamp
    private val errorNotified: MutableSet<String> = mutableSetOf()

    @PostConstruct
    fun start() {
        scope.launch {
            // Short delay — let other services stabilize.
            delay(20_000)
            while (true) {
                runCatching { tick() }
                    .onFailure { logger.warn(it) { "MeetingRecordingMonitor tick failed" } }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private suspend fun tick() {
        val now = Instant.now()
        val inFlight = meetingRepository
            .findByStateAndDeletedIsFalseOrderByStoppedAtAsc(MeetingStateEnum.RECORDING)
            .toList()
        for (meeting in inFlight) {
            try {
                checkStuck(meeting, now)
                checkHardCeiling(meeting, now)
            } catch (e: Exception) {
                logger.warn(e) { "monitor: failed check meeting=${meeting.id}" }
            }
        }
    }

    private suspend fun checkStuck(meeting: MeetingDocument, now: Instant) {
        val lastChunk = meeting.lastChunkAt ?: return  // not a pod recording yet
        val stuckFor = Duration.between(lastChunk, now)
        if (stuckFor < STUCK_THRESHOLD) return
        val meetingId = meeting.id.toHexString()
        val lastAlert = stuckAlerted[meetingId]
        if (lastAlert != null && Duration.between(lastAlert, now) < STUCK_RESPAM_INTERVAL) {
            return
        }
        stuckAlerted[meetingId] = now
        val clientId = meeting.clientId ?: return
        val title = meeting.title?.take(60) ?: "meeting"
        val description = buildString {
            append("Meeting **$title** upload stuck — ")
            append("${meeting.chunksReceived} chunks pending since ")
            append(lastChunk)
            append(". Pod health check needed.")
        }
        val task = TaskDocument(
            clientId = clientId,
            projectId = meeting.projectId,
            taskName = "[Meeting] Upload stuck: $title",
            content = description,
            state = TaskStateEnum.USER_TASK,
            type = TaskTypeEnum.SYSTEM,
            sourceUrn = SourceUrn(
                "o365-browser-pool::event:meeting-upload-stuck:$meetingId",
            ),
            pendingUserQuestion = "Meeting upload stuck — check pod?",
            userQuestionContext = description,
            priorityScore = 90,
            lastActivityAt = now,
        )
        taskRepository.save(task)
        notificationRpc.emitUserTaskCreated(
            clientId = clientId.toString(),
            taskId = task.id.toString(),
            title = task.taskName,
            interruptAction = "meeting_stuck",
            interruptDescription = description,
            connectionName = meeting.title ?: "Meeting",
        )
        logger.warn {
            "MEETING_STUCK | meeting=$meetingId chunks=${meeting.chunksReceived} " +
                "lastChunkAt=$lastChunk stuckFor=${stuckFor.toMinutes()}min"
        }
    }

    private suspend fun checkHardCeiling(meeting: MeetingDocument, now: Instant) {
        val endTime = scheduledEndFor(meeting) ?: return
        val overrun = Duration.between(endTime, now)
        if (overrun < HARD_CEILING_OVERRUN) return
        val meetingId = meeting.id.toHexString()
        val alreadyDispatched = leaveDispatchedAt[meetingId]
        if (alreadyDispatched == null) {
            val connectionId = meeting.clientId?.toString()
            if (connectionId != null) {
                val ok = browserPodMeetingClient.dispatchLeave(
                    connectionId = connectionId,
                    meetingId = meetingId,
                    reason = "scheduled_overrun",
                )
                leaveDispatchedAt[meetingId] = now
                logger.warn {
                    "MEETING_HARD_CEILING_LEAVE | meeting=$meetingId overrun=" +
                        "${overrun.toMinutes()}min dispatched=$ok"
                }
            }
            return
        }
        // Already dispatched — give the pod 5 min grace, then error.
        val sinceDispatch = Duration.between(alreadyDispatched, now)
        if (sinceDispatch < HARD_CEILING_GRACE) return
        if (meetingId in errorNotified) return
        errorNotified += meetingId
        val clientId = meeting.clientId ?: return
        val title = meeting.title?.take(60) ?: "meeting"
        val description =
            "Jervis uvízl v meetingu **$title** — pod nereaguje na leave. " +
                "Opusť meeting manuálně."
        val task = TaskDocument(
            clientId = clientId,
            projectId = meeting.projectId,
            taskName = "[Meeting] Jervis uvízl v meetingu — zásah potřebný",
            content = description,
            state = TaskStateEnum.USER_TASK,
            type = TaskTypeEnum.SYSTEM,
            sourceUrn = SourceUrn(
                "o365-browser-pool::event:meeting-stuck-error:$meetingId",
            ),
            pendingUserQuestion = "Jervis uvízl v meetingu",
            userQuestionContext = description,
            priorityScore = 95,
            lastActivityAt = now,
        )
        taskRepository.save(task)
        notificationRpc.emitUserTaskCreated(
            clientId = clientId.toString(),
            taskId = task.id.toString(),
            title = task.taskName,
            interruptAction = "meeting_stuck_error",
            interruptDescription = description,
            connectionName = meeting.title ?: "Meeting",
        )
    }

    private suspend fun scheduledEndFor(meeting: MeetingDocument): Instant? {
        // Find the CALENDAR_PROCESSING task whose recordingMeetingId points here.
        val clientId = meeting.clientId ?: return null
        return runCatching {
            val candidates = taskRepository
                .findByClientIdAndType(clientId, TaskTypeEnum.SYSTEM)
                .toList()
            candidates.firstOrNull {
                it.meetingMetadata?.recordingMeetingId == meeting.id.toHexString()
            }?.meetingMetadata?.endTime
        }.getOrNull()
    }

    companion object {
        private const val TICK_INTERVAL_MS = 60_000L
        private val STUCK_THRESHOLD = Duration.ofMinutes(5)
        private val STUCK_RESPAM_INTERVAL = Duration.ofHours(1)
        private val HARD_CEILING_OVERRUN = Duration.ofMinutes(30)
        private val HARD_CEILING_GRACE = Duration.ofMinutes(5)
    }
}
