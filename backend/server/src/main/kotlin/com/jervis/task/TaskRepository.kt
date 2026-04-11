package com.jervis.task

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.common.types.TaskId
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.task.ProcessingMode
import com.jervis.task.TaskDocument
import kotlinx.coroutines.flow.Flow
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface TaskRepository : CoroutineCrudRepository<TaskDocument, TaskId> {
    /**
     * Find task by ID. Use this instead of the inherited findById(TaskId) to avoid
     * AOP proxy issues with Kotlin inline value classes.
     */
    suspend fun getById(id: TaskId): TaskDocument?

    suspend fun findAllByOrderByCreatedAtAsc(): Flow<TaskDocument>

    suspend fun findByStateOrderByCreatedAtAsc(state: TaskStateEnum): Flow<TaskDocument>

    suspend fun findByClientIdAndStateOrderByCreatedAtAsc(
        clientId: ClientId,
        state: TaskStateEnum,
    ): Flow<TaskDocument>

    suspend fun countByStateAndClientId(
        state: TaskStateEnum,
        clientId: ClientId,
    ): Long

    suspend fun findByTypeAndStateOrderByCreatedAtAsc(
        type: TaskTypeEnum,
        state: TaskStateEnum,
    ): Flow<TaskDocument>

    suspend fun findByTypeOrderByCreatedAtAsc(type: TaskTypeEnum): Flow<TaskDocument>

    suspend fun countByTypeAndState(
        type: TaskTypeEnum,
        state: TaskStateEnum,
    ): Long

    suspend fun countByType(type: TaskTypeEnum): Long

    suspend fun countByState(state: TaskStateEnum): Long

    suspend fun findOneByScheduledAtLessThanAndTypeOrderByScheduledAtAsc(
        scheduledAt: Instant,
        type: TaskTypeEnum,
    ): TaskDocument?

    suspend fun findByProjectIdAndType(
        projectId: ProjectId,
        type: TaskTypeEnum,
    ): Flow<TaskDocument>

    suspend fun findByClientIdAndType(
        clientId: ClientId,
        type: TaskTypeEnum,
    ): Flow<TaskDocument>

    suspend fun countByStateAndTypeAndClientId(
        state: TaskStateEnum,
        type: TaskTypeEnum,
        clientId: ClientId,
    ): Long

    // NEW: Processing mode queries for FOREGROUND/BACKGROUND task processing

    /**
     * Find FOREGROUND tasks ordered by queuePosition (for chat processing).
     * User can reorder tasks in UI by changing queuePosition.
     */
    suspend fun findByProcessingModeAndStateOrderByQueuePositionAsc(
        processingMode: ProcessingMode,
        state: TaskStateEnum,
    ): Flow<TaskDocument>

    /**
     * Find tasks by processingMode matching any of the given states, ordered by queuePosition.
     * Used for queue display: includes both QUEUED and PROCESSING tasks
     * so inline messages sent during orchestration are visible in the UI queue.
     */
    suspend fun findByProcessingModeAndStateInOrderByQueuePositionAsc(
        processingMode: ProcessingMode,
        states: Collection<TaskStateEnum>,
    ): Flow<TaskDocument>

    /**
     * Find BACKGROUND tasks ordered by createdAt (oldest first, FIFO).
     * Background tasks process in creation order.
     */
    suspend fun findByProcessingModeAndStateOrderByCreatedAtAsc(
        processingMode: ProcessingMode,
        state: TaskStateEnum,
    ): Flow<TaskDocument>

    /**
     * Find FOREGROUND task for specific project/client (for chat reuse).
     */
    suspend fun findByProcessingModeAndClientIdAndProjectIdAndType(
        processingMode: ProcessingMode,
        clientId: ClientId,
        projectId: ProjectId?,
        type: TaskTypeEnum,
    ): Flow<TaskDocument>

    /**
     * Count tasks by processing mode and state.
     */
    suspend fun countByProcessingModeAndState(
        processingMode: ProcessingMode,
        state: TaskStateEnum,
    ): Long

    /**
     * Count FOREGROUND queue: tasks by processingMode + state + type + clientId.
     */
    suspend fun countByProcessingModeAndStateAndTypeAndClientId(
        processingMode: ProcessingMode,
        state: TaskStateEnum,
        type: TaskTypeEnum,
        clientId: ClientId,
    ): Long

    /**
     * Find BACKGROUND tasks ordered by queuePosition (if set) then createdAt.
     * Supports user-reordered background queue while falling back to FIFO for unordered tasks.
     */
    suspend fun findByProcessingModeAndStateOrderByQueuePositionAscCreatedAtAsc(
        processingMode: ProcessingMode,
        state: TaskStateEnum,
    ): Flow<TaskDocument>

    /**
     * Find all pending tasks for a client by processing mode and state.
     * Used for queue management UI display.
     */
    suspend fun findByClientIdAndProcessingModeAndState(
        clientId: ClientId,
        processingMode: ProcessingMode,
        state: TaskStateEnum,
    ): Flow<TaskDocument>

    /**
     * Find tasks ready for qualification where backoff window has elapsed.
     * Returns tasks where nextQualificationRetryAt is null (new) or <= now (backoff expired).
     */
    suspend fun findByStateAndNextQualificationRetryAtIsNullOrderByCreatedAtAsc(
        state: TaskStateEnum,
    ): Flow<TaskDocument>

    suspend fun findByStateAndNextQualificationRetryAtLessThanEqualOrderByCreatedAtAsc(
        state: TaskStateEnum,
        now: Instant,
    ): Flow<TaskDocument>

    /**
     * Find tasks for qualification with priority ordering: queuePosition ASC (nulls last), createdAt ASC.
     * Manually prioritized items (queuePosition != null) are processed first, then FIFO.
     */
    suspend fun findByStateAndNextQualificationRetryAtIsNullOrderByQueuePositionAscCreatedAtAsc(
        state: TaskStateEnum,
    ): Flow<TaskDocument>

    suspend fun findByStateAndNextQualificationRetryAtLessThanEqualOrderByQueuePositionAscCreatedAtAsc(
        state: TaskStateEnum,
        now: Instant,
    ): Flow<TaskDocument>

    /**
     * Find a single task by type matching any of the given states.
     * Used by BackgroundEngine idle review loop to check for existing IDLE_REVIEW tasks.
     */
    suspend fun findFirstByTypeAndStateIn(
        type: TaskTypeEnum,
        states: Collection<TaskStateEnum>,
    ): TaskDocument?

    /**
     * Find all scheduled tasks due for dispatch (scheduledAt <= threshold).
     * Used by BackgroundEngine scheduler loop.
     */
    suspend fun findByScheduledAtLessThanEqualAndTypeAndStateOrderByScheduledAtAsc(
        scheduledAt: Instant,
        type: TaskTypeEnum,
        state: TaskStateEnum,
    ): Flow<TaskDocument>

    /**
     * Find all scheduled tasks that follow user timezone (personal reminders).
     * Scheduler recalculates their scheduledAt based on current user timezone.
     */
    fun findByFollowUserTimezoneAndTypeAndState(
        followUserTimezone: Boolean,
        type: TaskTypeEnum,
        state: TaskStateEnum,
    ): Flow<TaskDocument>

    /**
     * Find recent tasks ordered by creation date (newest first).
     * Used by chat tool list_recent_tasks.
     */
    fun findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
        since: Instant,
    ): Flow<TaskDocument>

    /**
     * Find recent tasks by state ordered by creation date (newest first).
     * Used by chat tool list_recent_tasks with state filter.
     */
    fun findByStateAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
        state: TaskStateEnum,
        since: Instant,
    ): Flow<TaskDocument>

    /**
     * Find recent tasks by client ordered by creation date (newest first).
     */
    fun findByClientIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
        clientId: ClientId,
        since: Instant,
    ): Flow<TaskDocument>

    // EPIC 2: Priority-based background task ordering

    /**
     * Find BACKGROUND tasks ordered by priorityScore DESC (highest first), then createdAt ASC.
     * Used by getNextBackgroundTask() for priority-based scheduling.
     */
    suspend fun findByProcessingModeAndStateOrderByPriorityScoreDescCreatedAtAsc(
        processingMode: ProcessingMode,
        state: TaskStateEnum,
    ): Flow<TaskDocument>

    /**
     * Find task by correlationId for deduplication.
     * Used by AutoTaskCreationService to avoid creating duplicate tasks.
     */
    suspend fun findByProcessingModeAndStateInOrderByPriorityScoreDescCreatedAtAsc(
        processingMode: ProcessingMode,
        states: List<TaskStateEnum>,
    ): Flow<TaskDocument>

    suspend fun findByClientIdAndProcessingModeAndStateInOrderByPriorityScoreDescCreatedAtAsc(
        clientId: ClientId,
        processingMode: ProcessingMode,
        states: List<TaskStateEnum>,
    ): Flow<TaskDocument>

    suspend fun findByCorrelationId(correlationId: String): TaskDocument?

    suspend fun findFirstByTopicIdAndStateIn(
        topicId: String,
        states: List<TaskStateEnum>,
    ): TaskDocument?

    /**
     * Find active tasks for a client (for qualification context).
     * Returns tasks in active states ordered by most recent activity.
     */
    fun findByClientIdAndStateInOrderByLastActivityAtDesc(
        clientId: com.jervis.common.types.ClientId,
        states: List<TaskStateEnum>,
    ): Flow<TaskDocument>

    // Work plan hierarchy queries

    /**
     * Find all child tasks of a parent task.
     * Used by WorkPlanExecutor to check hierarchy completion.
     */
    fun findByParentTaskId(parentTaskId: TaskId): Flow<TaskDocument>

    /**
     * Find child tasks of a parent in a specific state.
     * Used by WorkPlanExecutor to find BLOCKED children for unblocking.
     */
    fun findByParentTaskIdAndState(parentTaskId: TaskId, state: TaskStateEnum): Flow<TaskDocument>

    /**
     * Count child tasks NOT in a given state.
     * Used to check if all children are DONE (countNot(DONE) == 0 → all done).
     */
    suspend fun countByParentTaskIdAndStateNot(parentTaskId: TaskId, state: TaskStateEnum): Long

    /**
     * Phase 3: Re-entrant qualifier — find tasks that need (re-)qualification.
     * Picked up by [com.jervis.task.RequalificationLoop]. The loop dispatches
     * each task to Python `/qualify` and clears the flag once a decision arrives.
     */
    fun findByNeedsQualificationTrueOrderByCreatedAtAsc(): Flow<TaskDocument>

    /**
     * Find all BLOCKED tasks ordered by phase order.
     * Used by WorkPlanExecutor to iterate BLOCKED tasks for dependency resolution.
     */
    fun findByStateOrderByOrderInPhaseAsc(state: TaskStateEnum): Flow<TaskDocument>

    /**
     * Find tasks by sourceUrn and state. Used by deadline scan dedup to check
     * if a scan is already pending before creating a new one.
     */
    suspend fun findBySourceUrnAndStateIn(
        sourceUrn: SourceUrn,
        states: List<TaskStateEnum>,
    ): List<TaskDocument>

    /**
     * Find tasks with attached meeting metadata whose approved meeting has
     * entered its live window (startTime ≤ now ≤ endTime) and that have NOT
     * yet been dispatched to the desktop recorder. Consumed by
     * `MeetingRecordingDispatcher` — approval is checked separately in the
     * `ApprovalQueueRepository` so this query stays a pure DB filter.
     *
     * The discriminator is `meetingMetadata != null`, NOT a task type — after
     * Phase 1 the calendar source identity lives in `sourceUrn`, not in
     * TaskTypeEnum. Embedded `meetingMetadata` fields are referenced via
     * dotted path. The `recordingDispatchedAt` predicate acts as the dedupe
     * lock.
     */
    @Query(
        "{ 'meetingMetadata': { '\$ne': null }, " +
            "'meetingMetadata.recordingDispatchedAt': null, " +
            "'meetingMetadata.startTime': { '\$lte': ?0 }, " +
            "'meetingMetadata.endTime': { '\$gt': ?0 } }"
    )
    fun findCalendarTasksReadyForRecordingDispatch(
        now: Instant,
    ): Flow<TaskDocument>
}
