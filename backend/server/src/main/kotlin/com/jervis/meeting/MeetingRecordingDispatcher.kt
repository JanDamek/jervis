package com.jervis.meeting

import com.jervis.dto.events.JervisEvent
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.task.ApprovalQueueRepository
import com.jervis.task.TaskDocument
import com.jervis.task.TaskRepository
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * Meeting recording dispatcher.
 *
 * Scans for `CALENDAR_PROCESSING` tasks whose embedded `MeetingMetadata` has
 * entered its live window (`startTime ≤ now ≤ endTime`) and whose
 * corresponding `ApprovalQueueDocument` has `status = APPROVED`. For each
 * matching task it emits exactly ONE `JervisEvent.MeetingRecordingTrigger` to
 * the approving client's desktop and marks the task with
 * `meetingMetadata.recordingDispatchedAt = now` so it will never be picked up
 * again on subsequent cycles.
 *
 * The desktop-side `DesktopMeetingRecorder` handles the trigger by spawning a
 * loopback audio capture (ffmpeg with platform-specific input: BlackHole on
 * macOS, WASAPI loopback on Windows, PulseAudio monitor on Linux) and
 * streaming 16kHz / 16-bit / mono PCM chunks to `MeetingRpc.uploadAudioChunk`.
 *
 * ## Read-only v1 invariants
 *
 * - NEVER triggers auto-join — the user joins the meeting themselves; Jervis
 *   only captures the audio their device is already playing. Per user rule
 *   `feedback-meeting-consent.md`.
 * - NEVER sends disclaimer messages into the meeting chat.
 * - Dedupe: the `recordingDispatchedAt` write acts as the lock. If it's ever
 *   `null` at read-time but a trigger already went out (process crash), the
 *   desktop recorder is idempotent via `MeetingRpc.startRecording`'s
 *   `(clientId, meetingType)` dedupe.
 *
 * The loop runs every 15s. That's intentionally short because the calendar
 * poller only runs every ~10min so the dispatch latency dominates UX.
 */
@Component
class MeetingRecordingDispatcher(
    private val taskRepository: TaskRepository,
    private val approvalQueueRepository: ApprovalQueueRepository,
    private val notificationRpc: NotificationRpcImpl,
    private val attenderClient: MeetingAttenderClient,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isStarted = AtomicBoolean(false)
    private var dispatchJob: Job? = null

    @PostConstruct
    fun start() {
        if (!isStarted.compareAndSet(false, true)) {
            logger.warn { "MeetingRecordingDispatcher.start() called twice — ignored" }
            return
        }
        dispatchJob = scope.launch {
            logger.info { "MeetingRecordingDispatcher loop STARTED (interval=${LOOP_INTERVAL_MS}ms)" }
            while (true) {
                try {
                    runDispatchCycle()
                } catch (e: Exception) {
                    logger.error(e) { "MeetingRecordingDispatcher cycle failed" }
                }
                delay(LOOP_INTERVAL_MS)
            }
        }
    }

    private suspend fun runDispatchCycle() {
        val now = Instant.now()
        val candidates: List<TaskDocument> = taskRepository
            .findCalendarTasksReadyForRecordingDispatch(now)
            .toList()
        if (candidates.isEmpty()) return

        for (task in candidates) {
            val metadata = task.meetingMetadata ?: continue
            val approval = approvalQueueRepository.findByTaskId(task.id.toString())
            if (approval == null || approval.status != "APPROVED") {
                // Task is inside the live window but user hasn't approved → skip.
                // The MeetingAttendApprovalService handles the approval prompt
                // via its own emitUserTaskCreated() path, independent of us.
                continue
            }

            val event = JervisEvent.MeetingRecordingTrigger(
                taskId = task.id.toString(),
                clientId = task.clientId.toString(),
                projectId = task.projectId?.toString(),
                title = task.taskName,
                startTime = metadata.startTime.toString(),
                endTime = metadata.endTime.toString(),
                provider = metadata.provider.name,
                joinUrl = metadata.joinUrl,
                timestamp = now.toString(),
            )

            // Prefer the desktop loopback recorder when the user has an
            // active event-stream subscription (a desktop or mobile app is
            // online and listening). Otherwise fall back to the K8s
            // attender pod which captures audio headlessly.
            val clientIdStr = task.clientId.toString()
            val dispatchKind: String
            val dispatchOk: Boolean
            if (notificationRpc.hasActiveSubscribers(clientIdStr)) {
                notificationRpc.emitEvent(clientIdStr, event)
                dispatchKind = "DESKTOP"
                dispatchOk = true
            } else {
                dispatchOk = attenderClient.attend(event)
                dispatchKind = "ATTENDER_POD"
            }

            if (!dispatchOk) {
                logger.warn {
                    "MeetingRecordingDispatcher: $dispatchKind dispatch failed for task=${task.id} " +
                        "— leaving recordingDispatchedAt null so the next cycle will retry"
                }
                continue
            }

            // Mark the task as dispatched so we never trigger twice. Re-fetch
            // to avoid clobbering concurrent writes from the approval service.
            val fresh = taskRepository.getById(task.id) ?: continue
            val updated = fresh.copy(
                meetingMetadata = fresh.meetingMetadata?.copy(
                    recordingDispatchedAt = now,
                ),
            )
            taskRepository.save(updated)

            logger.info {
                "MeetingRecordingDispatcher: dispatched ($dispatchKind) trigger for task=${task.id} " +
                    "client=${task.clientId} provider=${metadata.provider} " +
                    "title='${task.taskName}'"
            }
        }
    }

    companion object {
        private const val LOOP_INTERVAL_MS = 15_000L
    }
}
