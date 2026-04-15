package com.jervis.task

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.common.types.TaskId
import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * TaskDocument - SINGLE SOURCE OF TRUTH for tasks and conversations.
 *
 * ARCHITECTURE (2026-01):
 * - TaskDocument is the primary entity for all work (chat, scheduled tasks, user tasks)
 * - ONE TaskDocument = ONE active conversation/task per project
 * - Conversation history stored in separate chat_messages collection (linked by taskId)
 * - Agent checkpoint state stored in agentCheckpointJson for continuity
 * - Queue management integrated directly into TaskDocument (no separate queue collection)
 *
 * Processing Modes (three-tier priority: FOREGROUND > BACKGROUND > IDLE):
 * - FOREGROUND: Chat window processing - user waits for response, sees in UI
 *   - Processed by order: queuePosition ASC (user can reorder in UI)
 *   - User interacts via chat window
 *
 * - BACKGROUND: Autonomous processing - user-scheduled tasks via chat
 *   - Processed by order: priorityScore DESC, createdAt ASC
 *   - No chat interaction, runs automatically
 *   - Can escalate to USER_TASK if needs input
 *   - Preempts IDLE, never preempted by IDLE
 *
 * - IDLE: System idle work - runs only when nothing else to do
 *   - Lowest priority, pauses when any FOREGROUND or BACKGROUND arrives
 *   - Max one idle task at a time
 *
 * Movement between modes:
 * - Chat → Background: User moves TaskDocument to background (disappears from chat history)
 * - Background → Chat: User takes TaskDocument into chat (appears in chat, continues there)
 * - Same TaskDocument throughout - just changes processingMode field
 *
 * Chat Workflow (FOREGROUND, Reusable TaskDocument):
 * 1. First message: Create TaskDocument for project with processingMode=FOREGROUND
 * 2. User sends message: Save to ChatMessageDocument(taskId=task.id, role=USER, sequence=N)
 * 3. Agent processes: Restore from task.agentCheckpointJson, run agent
 * 4. Agent responds: Save to ChatMessageDocument(taskId=task.id, role=ASSISTANT, sequence=N+1)
 * 5. Save checkpoint: Update task.agentCheckpointJson with agent state
 * 6. Next message: REUSE same TaskDocument, append new messages to ChatMessageDocument
 * 7. Problem solved OR moved to background: TaskDocument can be moved to BACKGROUND mode
 *
 * Background Workflow (BACKGROUND):
 * 1. Task created with processingMode=BACKGROUND (Confluence, Jira, scheduled tasks)
 * 2. BackgroundEngine picks oldest QUEUED task with processingMode=BACKGROUND
 * 3. Agent processes autonomously (no chat window)
 * 4. If needs user input: state=USER_TASK (stays BACKGROUND until moved to chat)
 * 5. User can move to FOREGROUND (chat) to continue interactively
 *
 * Design Philosophy:
 * - All task information lives in the `content` field as plain text.
 * - No context/enrichment maps. The model must rely on `content` only.
 * - If provenance is needed, include a "Source:" line inside content, for example,
 *   - Source: email://<accountId>/<messageId>
 *   - Source: confluence://<accountId>/<pageId>
 *   - Source: git file://<projectId>/<commitHash>/<filePath>
 *
 * Agent Persistence:
 * - agentCheckpointJson stores serialized agent state (compressed after time)
 * - On next message, agent state is restored to continue where it left off
 * - Enables continuous conversation across multiple user messages
 * - Enables User Task iteration: agent resumes from checkpoint when task returns from USER_TASK state
 * - Agent can search chat history using ChatHistoryTools when needed
 *
 * Vision Augmentation:
 * - `attachments` contains binary references for vision model processing
 * - Qualifier Agent loads attachments on-demand and augments content with vision descriptions
 *
 * @property id Unique task identifier
 * @property type Task type (USER_INPUT_PROCESSING, SCHEDULED_TASK, USER_TASK, etc.)
 * @property taskName Display name for UI (default: "Unnamed Task")
 * @property content Original task description/user query
 * @property projectId Project context (null for general tasks)
 * @property clientId Client who owns this task
 * @property createdAt Task creation timestamp
 * @property state Current task state (NEW, INDEXING, QUEUED, PROCESSING, DONE, ERROR, etc.)
 * @property processingMode FOREGROUND (chat), BACKGROUND (user-scheduled), or IDLE (system idle work)
 * @property queuePosition Position in foreground queue (null for BACKGROUND)
 * @property correlationId Unique ID for tracing execution flow
 * @property sourceUrn Source of task (chat, email, Jira, etc.)
 * @property errorMessage Error details if state = ERROR
 * @property qualificationRetries Retry count for qualification failures
 * @property attachments Binary attachments for vision analysis
 * @property scheduledAt When task should run (for scheduled tasks)
 * @property cronExpression Cron expression (for recurring tasks)
 * @property agentCheckpointJson Serialized agent state for continuity (reused across messages)
 * @property orchestratorThreadId LangGraph thread ID when task is dispatched to Python orchestrator
 * @property orchestrationStartedAt Timestamp when task was dispatched to Python orchestrator (for detecting inline messages)
 */
@Document(collection = "tasks")
@CompoundIndexes(
    CompoundIndex(name = "state_type", def = "{'state': 1, 'type': 1}"),
    CompoundIndex(name = "state_type_createdAt", def = "{'state': 1, 'type': 1, 'createdAt': -1}"),
    CompoundIndex(name = "parent_state_idx", def = "{'parentTaskId': 1, 'state': 1}"),
    CompoundIndex(name = "queue_priority_idx", def = "{'processingMode': 1, 'state': 1, 'priorityScore': -1, 'createdAt': 1}"),
    CompoundIndex(name = "user_task_priority_idx", def = "{'type': 1, 'state': 1, 'priorityScore': -1, 'lastActivityAt': -1}"),
    CompoundIndex(name = "topic_idx", def = "{'topicId': 1, 'state': 1}"),
    CompoundIndex(name = "deadline_idx", def = "{'state': 1, 'deadline': 1}"),
)
data class TaskDocument(
    @Id
    val id: TaskId = TaskId.generate(),
    @Indexed
    val type: TaskTypeEnum,
    val taskName: String = "Unnamed Task",
    val content: String,
    val projectId: ProjectId? = null,
    @Indexed
    val clientId: ClientId,
    @Indexed
    val createdAt: Instant = Instant.now(),
    @Indexed
    var state: TaskStateEnum = TaskStateEnum.NEW,
    @Indexed
    val processingMode: ProcessingMode = ProcessingMode.BACKGROUND,
    val queuePosition: Int? = null,
    val correlationId: String = id.toString(),
    val sourceUrn: SourceUrn,
    val errorMessage: String? = null,
    val qualificationRetries: Int = 0,
    val nextQualificationRetryAt: Instant? = null,
    val attachments: List<AttachmentMetadata> = emptyList(),
    val scheduledAt: Instant? = null,
    val cronExpression: String? = null,
    /** Timezone in which cronExpression was defined (e.g. "Europe/Prague"). Immutable after creation. */
    val cronTimezone: String? = null,
    /**
     * If true, scheduledAt is recalculated from scheduledLocalTime + user's CURRENT timezone
     * before each dispatch check. Used for personal reminders that follow the user across timezones.
     * If false (default), scheduledAt is a fixed UTC instant.
     */
    val followUserTimezone: Boolean = false,
    /**
     * User-intended local time (ISO LocalDateTime, e.g. "2026-03-31T16:00").
     * Only meaningful when followUserTimezone=true. Scheduler resolves to UTC
     * using the user's current timezone from preference.
     */
    val scheduledLocalTime: String? = null,
    // NEW: Agent session persistence for continuous conversation
    val agentCheckpointJson: String? = null,
    // Python orchestrator thread ID for async dispatch (state = PROCESSING)
    val orchestratorThreadId: String? = null,
    // Timestamp when task was dispatched to Python orchestrator (for inline message detection)
    val orchestrationStartedAt: Instant? = null,
    // Dispatch retry tracking (exponential backoff when orchestrator unavailable)
    val dispatchRetryCount: Int = 0,
    val nextDispatchRetryAt: Instant? = null,
    // NEW (2026-01): User interaction state for async USER_TASK workflow
    /**
     * Question agent is waiting for user to answer (when state = USER_TASK).
     * On resume, agent knows: "user is answering THIS question".
     * Cleared when user responds and task returns to QUEUED.
     */
    val pendingUserQuestion: String? = null,
    /**
     * Context explaining WHY agent asked the question.
     * Helps user understand what agent needs and why.
     */
    val userQuestionContext: String? = null,
    // Non-blocking coding agent dispatch (state = CODING)
    /** K8s Job name for the coding agent (when dispatched async). */
    val agentJobName: String? = null,
    /** State of the agent job: RUNNING, SUCCEEDED, FAILED. */
    val agentJobState: String? = null,
    /** When the agent job was dispatched. */
    val agentJobStartedAt: Instant? = null,
    /** Workspace path on shared PVC for the coding agent. */
    val agentJobWorkspacePath: String? = null,
    /** Agent type: "claude" or "kilo". */
    val agentJobAgentType: String? = null,
    // Qualification progress history (persisted for viewing in "Hotovo" section)
    /** Timestamp when qualification actually started (not queue creation time). */
    val qualificationStartedAt: Instant? = null,
    /** Accumulated qualification progress steps — persisted for history display. */
    val qualificationSteps: List<QualificationStepRecord> = emptyList(),
    /** Accumulated orchestrator progress steps — persisted for history display. */
    val orchestratorSteps: List<OrchestratorStepRecord> = emptyList(),
    // EPIC 2: Priority-based scheduling (replaces FIFO for background tasks)
    /** Priority score 0–100, higher = more urgent. Used by getNextBackgroundTask() for ordering. */
    val priorityScore: Int? = null,
    /** Human-readable reason for the assigned priority. */
    val priorityReason: String? = null,
    // EPIC 2: Enhanced qualifier output for pipeline routing
    /** Inferred action type from qualifier (e.g., CODE_FIX, RESPOND_EMAIL). */
    val actionType: String? = null,
    /** Inferred complexity from qualifier (e.g., TRIVIAL, SIMPLE, MEDIUM, COMPLEX). */
    val estimatedComplexity: String? = null,
    // Work plan hierarchy: parent-child task decomposition
    /** Parent task ID for hierarchical task decomposition (null = root/standalone task). */
    @Indexed
    val parentTaskId: TaskId? = null,
    /** Task IDs that must complete (DONE) before this task can be unblocked. */
    val blockedByTaskIds: List<TaskId> = emptyList(),
    /** Phase name within a work plan (e.g., "architecture", "implementation", "testing"). */
    val phase: String? = null,
    /** Ordering within a phase (0-based). */
    val orderInPhase: Int = 0,
    // Indexing claim: atomic claim for KB processing (state stays INDEXING, claimed via timestamp)
    /** When this INDEXING task was claimed for KB dispatch. Null = unclaimed, available for pickup. */
    val indexingClaimedAt: Instant? = null,
    // Qualifier prepared context: GPU agent enriches task before orchestrator
    /** JSON context prepared by the qualifying GPU agent (KB search results, suggested approach). */
    val qualifierPreparedContext: String? = null,
    // Attachment extraction tracking
    /** MR/PR URL created by coding agent pipeline. */
    val mergeRequestUrl: String? = null,
    /** Whether this task has email/source attachments with extract records. */
    val hasAttachments: Boolean = false,
    /** Number of attachment extract records created for this task. */
    val attachmentCount: Int = 0,
    // KB result fields — saved from /internal/kb-done callback for orchestrator classification
    /** Short summary from KB extraction (for orchestrator first-step classification). */
    val kbSummary: String? = null,
    /** Entity keys discovered by KB graph extraction. */
    val kbEntities: List<String> = emptyList(),
    /** Whether KB determined the content is actionable (hint for orchestrator). */
    val kbActionable: Boolean = false,
    /** Topic ID for conversation consolidation — groups related items across all sources.
     * Format: "email-thread:<threadId>", "mr:<projectId>:<mrId>", "slack:<channelId>:<threadTs>", etc. */
    val topicId: String? = null,
    /** Last activity timestamp — updated when new thread messages arrive. Used for "K reakci" sorting. */
    val lastActivityAt: Instant? = null,
    /** Whether Jervis (@selfUsername) was mentioned in comments/discussion. Boosts priority. */
    val mentionsJervis: Boolean = false,
    /**
     * Meeting metadata for tasks that represent an upcoming/running online meeting.
     * Populated by calendar polling indexers (Google, Outlook). Non-null implies the
     * task is subject to the MEETING_ATTEND approval flow — NEVER triggers auto-join.
     */
    val meetingMetadata: MeetingMetadata? = null,
    /**
     * 2-3 sentence overview of the task content, set by the qualifier agent after KB ingestion.
     * Used in the chat brief and related-tasks list to give the user a quick summary.
     */
    val summary: String? = null,
    /**
     * Phase 3: Re-entrant qualifier flag.
     *
     * When true, the [com.jervis.task.RequalificationLoop] picks up the task
     * and dispatches it to the Python `/qualify` endpoint for (re-)reasoning.
     * Lifecycle events that set this true:
     *   - new INDEXING task created (set after KB ingest finishes)
     *   - all children of a BLOCKED parent reach DONE → parent unblocks
     *   - user responds to a USER_TASK and resumes the task
     *   - new related evidence indexed (future: topic-based fan-out)
     *
     * The loop clears this flag once the new qualifier decision arrives via
     * `/internal/qualification-done`.
     */
    @Indexed
    val needsQualification: Boolean = false,
    /**
     * Absolute instant by which a response MUST be out. Drives deadline-first scheduling
     * (nearest deadline wins over priority in same priority tier). Null = no deadline pressure
     * (legacy tasks, batch jobs). Populated by inbound handlers via StructuralUrgencyDetector.
     */
    @Indexed
    val deadline: Instant? = null,
    /**
     * Observed user presence at the moment this task was created. Used to scale the deadline
     * (active user → tight, offline → relaxed). Stored as enum name; UNKNOWN when presence
     * API is unavailable. See Presence enum.
     */
    val userPresence: String? = null,
) {
    companion object {
        /**
         * Spring Data factory with primitive types to work around Kotlin inline value class
         * parameter name mangling. Value class parameters get mangled names in bytecode
         * (e.g. `id` → `id-<hash>`), which breaks Spring Data's constructor resolution.
         * This factory uses raw ObjectId/String so Spring Data can match parameter names.
         */
        @PersistenceCreator
        @JvmStatic
        fun create(
            id: ObjectId,
            type: TaskTypeEnum,
            taskName: String?,
            content: String,
            projectId: ObjectId?,
            clientId: ObjectId,
            createdAt: Instant?,
            state: TaskStateEnum?,
            processingMode: ProcessingMode?,
            queuePosition: Int?,
            correlationId: String?,
            sourceUrn: String,
            errorMessage: String?,
            qualificationRetries: Int?,
            nextQualificationRetryAt: Instant?,
            attachments: List<AttachmentMetadata>?,
            scheduledAt: Instant?,
            cronExpression: String?,
            agentCheckpointJson: String?,
            orchestratorThreadId: String?,
            orchestrationStartedAt: Instant?,
            dispatchRetryCount: Int?,
            nextDispatchRetryAt: Instant?,
            pendingUserQuestion: String?,
            userQuestionContext: String?,
            agentJobName: String?,
            agentJobState: String?,
            agentJobStartedAt: Instant?,
            agentJobWorkspacePath: String?,
            agentJobAgentType: String?,
            qualificationStartedAt: Instant?,
            qualificationSteps: List<QualificationStepRecord>?,
            orchestratorSteps: List<OrchestratorStepRecord>?,
            priorityScore: Int?,
            priorityReason: String?,
            actionType: String?,
            estimatedComplexity: String?,
            parentTaskId: ObjectId?,
            blockedByTaskIds: List<ObjectId>?,
            phase: String?,
            orderInPhase: Int?,
            indexingClaimedAt: Instant?,
            qualifierPreparedContext: String?,
            mergeRequestUrl: String?,
            hasAttachments: Boolean?,
            attachmentCount: Int?,
            kbSummary: String?,
            kbEntities: List<String>?,
            kbActionable: Boolean?,
            topicId: String?,
            lastActivityAt: Instant?,
            mentionsJervis: Boolean?,
            meetingMetadata: MeetingMetadata?,
            summary: String?,
            needsQualification: Boolean?,
            deadline: Instant?,
            userPresence: String?,
        ): TaskDocument = TaskDocument(
            id = TaskId(id),
            type = type,
            taskName = taskName ?: "Unnamed Task",
            content = content,
            projectId = projectId?.let { ProjectId(it) },
            clientId = ClientId(clientId),
            createdAt = createdAt ?: Instant.now(),
            state = state ?: TaskStateEnum.NEW,
            processingMode = processingMode ?: ProcessingMode.BACKGROUND,
            queuePosition = queuePosition,
            correlationId = correlationId ?: id.toHexString(),
            sourceUrn = SourceUrn(sourceUrn),
            errorMessage = errorMessage,
            qualificationRetries = qualificationRetries ?: 0,
            nextQualificationRetryAt = nextQualificationRetryAt,
            attachments = attachments ?: emptyList(),
            scheduledAt = scheduledAt,
            cronExpression = cronExpression,
            agentCheckpointJson = agentCheckpointJson,
            orchestratorThreadId = orchestratorThreadId,
            orchestrationStartedAt = orchestrationStartedAt,
            dispatchRetryCount = dispatchRetryCount ?: 0,
            nextDispatchRetryAt = nextDispatchRetryAt,
            pendingUserQuestion = pendingUserQuestion,
            userQuestionContext = userQuestionContext,
            agentJobName = agentJobName,
            agentJobState = agentJobState,
            agentJobStartedAt = agentJobStartedAt,
            agentJobWorkspacePath = agentJobWorkspacePath,
            agentJobAgentType = agentJobAgentType,
            qualificationStartedAt = qualificationStartedAt,
            qualificationSteps = qualificationSteps ?: emptyList(),
            orchestratorSteps = orchestratorSteps ?: emptyList(),
            priorityScore = priorityScore,
            priorityReason = priorityReason,
            actionType = actionType,
            estimatedComplexity = estimatedComplexity,
            parentTaskId = parentTaskId?.let { TaskId(it) },
            blockedByTaskIds = blockedByTaskIds?.map { TaskId(it) } ?: emptyList(),
            phase = phase,
            orderInPhase = orderInPhase ?: 0,
            indexingClaimedAt = indexingClaimedAt,
            qualifierPreparedContext = qualifierPreparedContext,
            mergeRequestUrl = mergeRequestUrl,
            hasAttachments = hasAttachments ?: false,
            attachmentCount = attachmentCount ?: 0,
            kbSummary = kbSummary,
            kbEntities = kbEntities ?: emptyList(),
            kbActionable = kbActionable ?: false,
            topicId = topicId,
            lastActivityAt = lastActivityAt,
            mentionsJervis = mentionsJervis ?: false,
            meetingMetadata = meetingMetadata,
            summary = summary,
            needsQualification = needsQualification ?: false,
            deadline = deadline,
            userPresence = userPresence,
        )
    }
}

/**
 * ProcessingMode - determines how task is processed and queued.
 *
 * Three-tier priority hierarchy (highest → lowest):
 *
 * FOREGROUND:
 * - Chat window processing — user waits for response, sees in UI
 * - Ordered by queuePosition (user can reorder in UI)
 * - Takes precedence over BACKGROUND and IDLE tasks
 * - Preempts both BACKGROUND and IDLE
 *
 * BACKGROUND:
 * - Autonomous background processing scheduled via chat
 * - Ordered by priorityScore DESC, then createdAt ASC
 * - Runs when no FOREGROUND tasks
 * - Preempts IDLE tasks (but never preempted by IDLE)
 * - Examples: User-scheduled tasks, Confluence/Jira indexing
 *
 * IDLE:
 * - System idle work — lowest priority, runs only when nothing else to do
 * - Pauses immediately when any FOREGROUND or BACKGROUND task arrives
 * - Examples: Proactive code review, KB consistency check, vulnerability scan
 * - Max ONE idle task at a time per system
 */
enum class ProcessingMode {
    FOREGROUND,
    BACKGROUND,
    IDLE,
}

/**
 * A single qualification progress step, stored in TaskDocument for history.
 */
data class QualificationStepRecord(
    val timestamp: Instant = Instant.EPOCH,
    val step: String = "",
    val message: String = "",
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * A single orchestrator progress step, stored in TaskDocument for history.
 */
data class OrchestratorStepRecord(
    val timestamp: Instant = Instant.EPOCH,
    val node: String = "",
    val message: String = "",
    val goalIndex: Int = 0,
    val totalGoals: Int = 0,
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
)
