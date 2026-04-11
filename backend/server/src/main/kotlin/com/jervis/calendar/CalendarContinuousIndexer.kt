package com.jervis.calendar

import com.jervis.common.types.SourceUrn
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.infrastructure.polling.PollingStatusEnum
import com.jervis.task.MeetingMetadata
import com.jervis.task.MeetingProvider
import com.jervis.task.TaskRepository
import com.jervis.task.TaskService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Continuous indexer for calendar events.
 *
 * Polls calendar_event_index for NEW documents, creates CALENDAR_PROCESSING tasks,
 * and marks them as INDEXED. Follows the same pattern as EmailContinuousIndexer.
 */
@Service
@Order(13)
class CalendarContinuousIndexer(
    private val repository: CalendarEventIndexRepository,
    private val taskService: TaskService,
    private val taskRepository: TaskRepository,
) {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)
    private var indexerJob: Job? = null

    companion object {
        private const val POLL_DELAY_MS = 30_000L
    }

    @PostConstruct
    fun start() {
        indexerJob = scope.launch {
            runCatching { indexContinuously() }
                .onFailure { e -> logger.error(e) { "Calendar indexer crashed" } }
        }
    }

    @PreDestroy
    fun stop() {
        indexerJob?.cancel()
        supervisor.cancel()
    }

    private suspend fun indexContinuously() {
        logger.info { "CalendarContinuousIndexer started" }
        continuousNewEvents().collect { doc ->
            if (doc.state != PollingStatusEnum.NEW) return@collect
            try {
                indexEvent(doc)
            } catch (e: Exception) {
                logger.error(e) { "Failed to index calendar event ${doc.eventId}" }
                markAsFailed(doc, "Error: ${e.message}")
            }
        }
    }

    private fun continuousNewEvents() = flow {
        while (true) {
            val events = repository.findByStateOrderByStartTimeAsc(PollingStatusEnum.NEW).toList()
            if (events.isEmpty()) {
                delay(POLL_DELAY_MS)
            } else {
                for (event in events) {
                    emit(event)
                }
            }
        }
    }

    private suspend fun indexEvent(doc: CalendarEventIndexDocument) {
        val content = buildEventContent(doc)
        val correlationId = "calendar:${doc.eventId}"

        // Online meetings get scheduledAt + meetingMetadata so the meeting attend
        // approval flow can pick them up via the scheduler. Past events are still
        // indexed (KB record), but scheduledAt is omitted — there is nothing to
        // attend. Cancelled events never get scheduledAt either.
        val now = java.time.Instant.now()
        val isUpcomingOnlineMeeting = doc.isOnlineMeeting && !doc.isCancelled && doc.endTime.isAfter(now)
        val scheduledAt = if (isUpcomingOnlineMeeting) doc.startTime else null

        val meetingMetadata = if (doc.isOnlineMeeting) {
            MeetingMetadata(
                startTime = doc.startTime,
                endTime = doc.endTime,
                provider = MeetingProvider.fromJoinUrl(doc.onlineMeetingJoinUrl),
                joinUrl = doc.onlineMeetingJoinUrl,
                organizer = doc.organizer,
                attendees = doc.attendees,
                location = doc.location,
                isRecurring = doc.isRecurring,
            )
        } else {
            null
        }

        // Topic consolidates the recurring series in "K reakci" view; each instance
        // still gets its own task and its own approval — never a series-wide consent.
        val topicId = if (doc.isOnlineMeeting) {
            "calendar-meeting:${doc.calendarId}:${doc.eventId}"
        } else {
            null
        }

        // Upsert by correlationId — calendar events mutate (reschedule, cancel,
        // attendee changes) and the upstream poll loops the same eventId back
        // through this indexer with state=NEW whenever the etag changes.
        val existing = taskRepository.findByCorrelationId(correlationId)
        if (existing != null) {
            val updated = existing.copy(
                content = content,
                taskName = doc.title.take(120),
                scheduledAt = scheduledAt,
                topicId = topicId,
                meetingMetadata = meetingMetadata,
                projectId = doc.projectId ?: existing.projectId,
                state = if (doc.isCancelled) TaskStateEnum.DONE else existing.state,
                errorMessage = if (doc.isCancelled) "Calendar event cancelled" else existing.errorMessage,
                lastActivityAt = now,
            )
            taskRepository.save(updated)
            logger.info {
                "Calendar event reindexed: eventId=${doc.eventId} taskId=${existing.id} " +
                    "cancelled=${doc.isCancelled} scheduledAt=$scheduledAt"
            }
        } else {
            val task = taskService.createTask(
                taskType = TaskTypeEnum.SYSTEM,
                content = content,
                clientId = doc.clientId,
                correlationId = correlationId,
                sourceUrn = SourceUrn.calendar(
                    connectionId = doc.connectionId,
                    eventId = doc.eventId,
                    calendarId = doc.calendarId,
                ),
                projectId = doc.projectId,
                taskName = doc.title.take(120),
                scheduledAt = scheduledAt,
                topicId = topicId,
                meetingMetadata = meetingMetadata,
            )
            logger.info {
                "Calendar event indexed: eventId=${doc.eventId} title='${doc.title}' taskId=${task.id} " +
                    "online=${doc.isOnlineMeeting} scheduledAt=$scheduledAt"
            }
        }

        markAsIndexed(doc)
    }

    private fun buildEventContent(doc: CalendarEventIndexDocument): String {
        val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
        val parts = mutableListOf<String>()

        parts.add("Calendar Event: ${doc.title}")
        parts.add("Start: ${timeFormatter.format(doc.startTime)}")
        parts.add("End: ${timeFormatter.format(doc.endTime)}")

        if (doc.isAllDay) parts.add("All-day event")
        if (doc.location != null) parts.add("Location: ${doc.location}")
        if (doc.organizer != null) parts.add("Organizer: ${doc.organizer}")
        if (doc.attendees.isNotEmpty()) parts.add("Attendees: ${doc.attendees.joinToString(", ")}")
        if (doc.isRecurring) parts.add("Recurring event")
        if (doc.isOnlineMeeting) {
            val provider = MeetingProvider.fromJoinUrl(doc.onlineMeetingJoinUrl)
            parts.add("Online meeting: $provider")
            if (doc.onlineMeetingJoinUrl != null) parts.add("Join URL: ${doc.onlineMeetingJoinUrl}")
        }
        if (!doc.description.isNullOrBlank()) {
            parts.add("")
            parts.add(doc.description)
        }

        parts.add("")
        parts.add("Source: Google Calendar (${doc.calendarId})")

        return parts.joinToString("\n")
    }

    private suspend fun markAsIndexed(doc: CalendarEventIndexDocument) {
        // The index doc after INDEXED is a dedup + change-tracking marker. We
        // KEEP `etag` so the next poll can detect upstream updates, and we keep
        // `isCancelled` so re-emission of an already-cancelled event short
        // circuits. Heavy fields are dropped to keep storage lean — the live
        // data lives in the created TaskDocument from now on.
        repository.save(
            doc.copy(
                state = PollingStatusEnum.INDEXED,
                description = null,
                attendees = emptyList(),
                location = null,
                organizer = null,
                onlineMeetingJoinUrl = null,
            ),
        )
    }

    private suspend fun markAsFailed(doc: CalendarEventIndexDocument, error: String) {
        repository.save(doc.copy(state = PollingStatusEnum.FAILED, indexingError = error))
    }
}
