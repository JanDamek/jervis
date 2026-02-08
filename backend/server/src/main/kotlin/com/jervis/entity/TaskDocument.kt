package com.jervis.entity

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.common.types.TaskId
import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import org.springframework.data.annotation.Id
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
 * Processing Modes (FOREGROUND vs BACKGROUND):
 * - FOREGROUND: Chat window processing - user waits for response, sees in UI
 *   - Processed by order: queuePosition ASC (user can reorder in UI)
 *   - User interacts via chat window
 *
 * - BACKGROUND: Autonomous processing - runs independently when no foreground tasks
 *   - Processed by order: createdAt ASC (oldest first, FIFO)
 *   - No chat interaction, runs automatically
 *   - Can escalate to USER_TASK if needs input
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
 * 2. BackgroundEngine picks oldest READY_FOR_GPU task with processingMode=BACKGROUND
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
 * @property state Current task state (NEW, READY_FOR_GPU, RUNNING, DONE, ERROR, etc.)
 * @property processingMode FOREGROUND (chat) or BACKGROUND (autonomous)
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
 */
@Document(collection = "tasks")
data class TaskDocument(
    @Id
    val id: TaskId = TaskId.generate(),
    @Indexed
    val type: TaskTypeEnum,
    val taskName: String = "Unnamed Task",
    val content: String,
    val projectId: ProjectId? = null,
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
    // NEW: Agent session persistence for continuous conversation
    val agentCheckpointJson: String? = null,
    // Python orchestrator thread ID for async dispatch (state = PYTHON_ORCHESTRATING)
    val orchestratorThreadId: String? = null,
    // NEW (2026-01): User interaction state for async USER_TASK workflow
    /**
     * Question agent is waiting for user to answer (when state = USER_TASK).
     * On resume, agent knows: "user is answering THIS question".
     * Cleared when user responds and task returns to READY_FOR_GPU.
     */
    val pendingUserQuestion: String? = null,
    /**
     * Context explaining WHY agent asked the question.
     * Helps user understand what agent needs and why.
     */
    val userQuestionContext: String? = null,
)

/**
 * ProcessingMode - determines how task is processed and queued.
 *
 * FOREGROUND:
 * - Chat window processing
 * - User waits for response, sees in UI
 * - Ordered by queuePosition (user can reorder in UI)
 * - Takes precedence over BACKGROUND tasks
 *
 * BACKGROUND:
 * - Autonomous background processing
 * - Runs independently when no FOREGROUND tasks
 * - Ordered by createdAt (oldest first, FIFO)
 * - Examples: Confluence indexing, Jira polling, scheduled tasks
 */
enum class ProcessingMode {
    FOREGROUND,
    BACKGROUND,
}
