package com.jervis.meeting

import com.jervis.chat.ChatMessageService
import com.jervis.chat.MessageRole
import com.jervis.dto.pipeline.ApprovalAction
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.infrastructure.notification.ApnsPushService
import com.jervis.infrastructure.notification.FcmPushService
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.task.ApprovalQueueDocument
import com.jervis.task.ApprovalQueueRepository
import com.jervis.task.MeetingMetadata
import com.jervis.task.TaskDocument
import com.jervis.task.TaskRepository
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * MeetingAttendApprovalService — drives the per-meeting approval flow.
 *
 * Why a dedicated loop instead of reusing BackgroundEngine.runSchedulerLoop:
 *  - The generic scheduler only dispatches tasks of type SCHEDULED_TASK and
 *    transitions them NEW → INDEXING. CALENDAR_PROCESSING tasks have a very
 *    different lifecycle: they wait on user approval, support a dual trigger
 *    (preroll + at-start fallback), and never run cron repetition.
 *  - Keeping the meeting loop separate prevents future meeting-attend changes
 *    from leaking into the generic scheduler and vice versa.
 *
 * Lifecycle for one meeting task:
 *   1. CalendarContinuousIndexer creates a CALENDAR_PROCESSING task with
 *      scheduledAt = meeting startTime and meetingMetadata.
 *   2. This loop polls every 60s for tasks whose scheduledAt is within the
 *      preroll window (now + prerollMinutes).
 *   3. First touch → create ApprovalQueueDocument(PENDING) + push +
 *      ALERT chat bubble in the project conversation. The task stays NEW so
 *      we'll see it again on the next tick.
 *   4. Already-PENDING tasks reaching the at-start moment get a second push
 *      + bubble (the at-start fallback). Multi-device "first wins" is
 *      enforced by the broadcast push and by the single ApprovalQueue row.
 *   5. Past endTime with no decision → task transitions to DONE (timed out)
 *      and the queue entry is closed. No retroactive escalation: the user
 *      explicitly asked for no overdue spam on already-finished meetings.
 *   6. Approved meetings will be picked up by the recording pipeline in a
 *      later phase. This service is currently the approval-side only.
 *
 * Read-only contract: the service NEVER joins, sends a message, or talks back
 * — even disclaimer messages are forbidden in this first phase.
 */
@Service
class MeetingAttendApprovalService(
    private val taskRepository: TaskRepository,
    private val approvalQueueRepository: ApprovalQueueRepository,
    private val notificationRpc: NotificationRpcImpl,
    private val fcmPushService: FcmPushService,
    private val apnsPushService: ApnsPushService,
    private val chatMessageService: ChatMessageService,
    @Value("\${jervis.meeting-attend.enabled:true}")
    private val enabled: Boolean,
    @Value("\${jervis.meeting-attend.preroll-minutes:10}")
    private val prerollMinutes: Long,
    @Value("\${jervis.meeting-attend.poll-interval-seconds:60}")
    private val pollIntervalSeconds: Long,
) {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)
    private var loopJob: Job? = null

    @PostConstruct
    fun start() {
        if (!enabled) {
            logger.info { "MeetingAttendApprovalService disabled (jervis.meeting-attend.enabled=false)" }
            return
        }
        loopJob = scope.launch {
            logger.info {
                "MeetingAttendApprovalService started: prerollMinutes=$prerollMinutes pollInterval=${pollIntervalSeconds}s"
            }
            runLoop()
        }
    }

    @PreDestroy
    fun stop() {
        loopJob?.cancel()
        supervisor.cancel()
    }

    private suspend fun runLoop() {
        while (true) {
            try {
                processDueMeetings()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "MeetingAttendApprovalService loop tick failed" }
            }
            delay(Duration.ofSeconds(pollIntervalSeconds).toMillis())
        }
    }

    private suspend fun processDueMeetings() {
        val window = Instant.now().plus(Duration.ofMinutes(prerollMinutes))
        val due = taskRepository.findByScheduledAtLessThanEqualAndTypeAndStateOrderByScheduledAtAsc(
            scheduledAt = window,
            type = TaskTypeEnum.CALENDAR_PROCESSING,
            state = TaskStateEnum.NEW,
        )
        due.collect { task ->
            try {
                handleMeetingTask(task)
            } catch (e: Exception) {
                logger.error(e) { "Failed to handle meeting task ${task.id}" }
            }
        }
    }

    private suspend fun handleMeetingTask(task: TaskDocument) {
        val meta = task.meetingMetadata
        if (meta == null) {
            // Calendar event without meeting metadata = nothing to attend.
            // Mark DONE so we don't keep polling it forever.
            taskRepository.save(
                task.copy(
                    state = TaskStateEnum.DONE,
                    scheduledAt = null,
                    lastActivityAt = Instant.now(),
                    errorMessage = "Skipped: not an online meeting",
                ),
            )
            return
        }

        val now = Instant.now()
        val taskIdStr = task.id.toString()

        // Already over → close the task and any pending approval.
        if (meta.endTime.isBefore(now)) {
            val pending = approvalQueueRepository.findByTaskId(taskIdStr)
            if (pending != null && pending.status == "PENDING") {
                approvalQueueRepository.save(
                    pending.copy(status = "EXPIRED", respondedAt = now),
                )
            }
            taskRepository.save(
                task.copy(
                    state = TaskStateEnum.DONE,
                    scheduledAt = null,
                    lastActivityAt = now,
                    errorMessage = "Meeting ended without approval",
                ),
            )
            logger.info { "MEETING_ATTEND_TIMEOUT: taskId=$taskIdStr ended at ${meta.endTime}" }
            return
        }

        val existing = approvalQueueRepository.findByTaskId(taskIdStr)
        if (existing != null) {
            // Already a queue entry. Re-trigger only at the at-start moment
            // for still-PENDING approvals (the fallback push).
            if (existing.status == "PENDING" && shouldFireAtStartFallback(task, meta, now)) {
                emitPushAndBubble(task, meta, atStart = true)
                taskRepository.save(task.copy(lastActivityAt = now))
                logger.info { "MEETING_ATTEND_REPUSH: taskId=$taskIdStr at-start fallback fired" }
            }
            return
        }

        // First contact — create the approval queue entry, push, and bubble.
        approvalQueueRepository.save(
            ApprovalQueueDocument(
                taskId = taskIdStr,
                clientId = task.clientId.toString(),
                projectId = task.projectId?.toString(),
                action = ApprovalAction.MEETING_ATTEND.name,
                preview = buildPreview(task, meta),
                context = "Jervis se připojí pasivně, nahraje audio a přepíše. " +
                    "V této verzi nikdy neposílá zprávy ani zvuk.",
                riskLevel = "LOW",
                payload = mapOf(
                    "joinUrl" to (meta.joinUrl ?: ""),
                    "provider" to meta.provider.name,
                    "startTime" to meta.startTime.toString(),
                    "endTime" to meta.endTime.toString(),
                    "organizer" to (meta.organizer ?: ""),
                ),
                status = "PENDING",
            ),
        )
        emitPushAndBubble(task, meta, atStart = false)
        taskRepository.save(task.copy(lastActivityAt = now))
        logger.info {
            "MEETING_ATTEND_REQUEST: taskId=$taskIdStr title='${task.taskName}' " +
                "start=${meta.startTime} provider=${meta.provider}"
        }
    }

    /**
     * The at-start fallback should fire once we're inside the meeting window
     * and we haven't fired it yet on this tick. We use lastActivityAt as a
     * lightweight de-duplication marker — if it was bumped by the preroll
     * within the last `pollIntervalSeconds * 1.5` we treat that as "preroll
     * just happened, don't double-fire". This avoids two pushes back-to-back
     * for meetings that fall straight inside the preroll window.
     */
    private fun shouldFireAtStartFallback(task: TaskDocument, meta: MeetingMetadata, now: Instant): Boolean {
        if (now.isBefore(meta.startTime)) return false
        if (meta.endTime.isBefore(now)) return false
        val lastActivity = task.lastActivityAt ?: return true
        val freshThreshold = Duration.ofSeconds((pollIntervalSeconds * 1.5).toLong())
        return Duration.between(lastActivity, now) > freshThreshold
    }

    private fun buildPreview(task: TaskDocument, meta: MeetingMetadata): String {
        val title = task.taskName ?: "Online schůzka"
        val provider = meta.provider.name
        val starts = meta.startTime.toString()
        val organizer = meta.organizer?.let { " od $it" } ?: ""
        return "Schválit účast Jervise: \"$title\" ($provider, $starts$organizer)"
    }

    private suspend fun emitPushAndBubble(
        task: TaskDocument,
        meta: MeetingMetadata,
        atStart: Boolean,
    ) {
        val clientId = task.clientId.toString()
        val projectId = task.projectId?.toString()
        val taskIdStr = task.id.toString()

        val pushTitle = if (atStart) "Schůzka začíná!" else "Účast na schůzce?"
        val meetingLabel = task.taskName ?: "Online meeting"
        val pushBody = "$meetingLabel (${meta.provider.name})"

        // 1) kRPC stream — in-app dialog when the user is connected.
        notificationRpc.emitUserTaskCreated(
            clientId = clientId,
            taskId = taskIdStr,
            title = pushTitle,
            interruptAction = "meeting_attend",
            interruptDescription = pushBody,
            isApproval = true,
            projectId = projectId,
        )

        // 2) Push payload — broadcast to all registered devices.
        //    Multi-device "first wins": whoever taps first wins; the others see
        //    the queue entry already resolved and the push becomes a no-op.
        val pushData = buildMap {
            put("taskId", taskIdStr)
            put("type", "approval")
            put("isApproval", "true")
            put("interruptAction", "meeting_attend")
            put("provider", meta.provider.name)
            meta.joinUrl?.let { put("joinUrl", it) }
            put("startTime", meta.startTime.toString())
            put("endTime", meta.endTime.toString())
            put("trigger", if (atStart) "at_start" else "preroll")
        }
        runCatching {
            fcmPushService.sendPushNotification(
                clientId = clientId,
                title = pushTitle,
                body = pushBody,
                data = pushData,
            )
        }.onFailure { logger.warn { "FCM push failed for meeting task $taskIdStr: ${it.message}" } }
        runCatching {
            apnsPushService.sendPushNotification(
                clientId = clientId,
                title = pushTitle,
                body = pushBody,
                data = pushData,
            )
        }.onFailure { logger.warn { "APNs push failed for meeting task $taskIdStr: ${it.message}" } }

        // 3) Chat bubble. Posted into the meeting task's own conversation
        //    (conversationId = TaskDocument._id) so it surfaces under the
        //    project chat scope query and lands in the K reakci panel via
        //    metadata.needsReaction=true.
        val bubbleBody = buildString {
            append("**$pushTitle** ")
            append(pushBody)
            append("\n\n")
            if (atStart) {
                append("Schůzka právě začíná. Schválit připojení Jervise jako pasivního posluchače?")
            } else {
                append("Schválit připojení Jervise jako pasivního posluchače? ")
                append("První verze pouze nahrává a přepisuje, neposílá zprávy.")
            }
        }
        runCatching {
            chatMessageService.addMessage(
                conversationId = task.id.value,
                role = MessageRole.ALERT,
                content = bubbleBody,
                correlationId = task.correlationId ?: "meeting_attend:$taskIdStr",
                metadata = mapOf(
                    "needsReaction" to "true",
                    "approvalAction" to ApprovalAction.MEETING_ATTEND.name,
                    "taskId" to taskIdStr,
                    "joinUrl" to (meta.joinUrl ?: ""),
                    "trigger" to if (atStart) "at_start" else "preroll",
                ),
                clientId = clientId,
                projectId = projectId,
            )
        }.onFailure { logger.warn { "Chat bubble write failed for meeting task $taskIdStr: ${it.message}" } }
    }
}
