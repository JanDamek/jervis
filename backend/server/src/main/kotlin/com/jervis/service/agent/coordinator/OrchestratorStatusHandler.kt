package com.jervis.service.agent.coordinator

import com.jervis.common.types.TaskId
import com.jervis.dto.TaskStateEnum
import com.jervis.entity.TaskDocument
import com.jervis.repository.ChatMessageRepository
import com.jervis.repository.TaskRepository
import com.jervis.rpc.AgentOrchestratorRpcImpl
import com.jervis.service.background.TaskService
import com.jervis.service.task.UserTaskService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Handles orchestrator task status changes — both push-based callbacks
 * from Python and safety-net polling from BackgroundEngine.
 *
 * Push-based (primary): Python → POST /internal/orchestrator-status → this handler
 * Polling (safety net, 60s): BackgroundEngine → getStatus() → this handler
 *
 * State transitions:
 * - "done" → DISPATCHED_GPU (or re-queue if inline messages arrived)
 * - "interrupted" → USER_TASK (clarification or approval)
 * - "error" → ERROR + escalate
 */
@Service
class OrchestratorStatusHandler(
    private val taskRepository: TaskRepository,
    private val taskService: TaskService,
    private val userTaskService: UserTaskService,
    private val agentOrchestratorRpc: AgentOrchestratorRpcImpl,
    private val chatMessageRepository: ChatMessageRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Handle a status change for a Python-orchestrated task.
     *
     * Called from:
     * - KtorRpcServer /internal/orchestrator-status (push-based, real-time)
     * - BackgroundEngine.checkOrchestratorTaskStatus (polling safety net, 60s)
     *
     * @param taskId MongoDB ObjectId string of the task
     * @param status "done", "error", "interrupted", "running"
     * @param summary Final result summary (for "done")
     * @param error Error message (for "error")
     * @param interruptAction Interrupt type (for "interrupted"): "clarify", "commit", "push"
     * @param interruptDescription Human-readable interrupt description
     * @param branch Git branch name (for "done")
     * @param artifacts List of changed files (for "done")
     */
    suspend fun handleStatusChange(
        taskId: String,
        status: String,
        summary: String? = null,
        error: String? = null,
        interruptAction: String? = null,
        interruptDescription: String? = null,
        branch: String? = null,
        artifacts: List<String> = emptyList(),
    ) {
        val task = try {
            taskRepository.findById(TaskId(ObjectId(taskId)))
        } catch (e: Exception) {
            logger.warn { "ORCHESTRATOR_STATUS_HANDLER: invalid taskId=$taskId" }
            return
        }

        if (task == null) {
            logger.warn { "ORCHESTRATOR_STATUS_HANDLER: task not found taskId=$taskId" }
            return
        }

        // Only process tasks in PYTHON_ORCHESTRATING state
        if (task.state != TaskStateEnum.PYTHON_ORCHESTRATING) {
            logger.debug { "ORCHESTRATOR_STATUS_HANDLER: task $taskId not in PYTHON_ORCHESTRATING (state=${task.state}), skipping" }
            return
        }

        when (status) {
            "running" -> {
                // Still working — skip
            }
            "interrupted" -> handleInterrupted(task, interruptAction, interruptDescription)
            "done" -> handleDone(task, summary)
            "error" -> handleError(task, error)
            else -> {
                logger.debug { "ORCHESTRATOR_STATUS_HANDLER: unknown status=$status for task $taskId" }
            }
        }
    }

    private suspend fun handleInterrupted(
        task: TaskDocument,
        interruptAction: String?,
        interruptDescription: String?,
    ) {
        val action = interruptAction ?: "unknown"
        val description = interruptDescription ?: "Schválení vyžadováno"

        // Clarification vs approval: different question format for the user
        val (pendingQuestion, questionContext, phase) = when (action) {
            "clarify" -> Triple(
                description,  // Questions directly, no "Schválení:" prefix
                "Orchestrátor potřebuje upřesnění před zahájením práce",
                "clarification",
            )
            else -> Triple(
                "Schválení: $action – $description",
                "Python orchestrátor potřebuje schválení pro: $action",
                "approval",
            )
        }

        userTaskService.failAndEscalateToUserTask(
            task = task,
            reason = "ORCHESTRATOR_INTERRUPT",
            pendingQuestion = pendingQuestion,
            questionContext = questionContext,
            interruptAction = action,
            isApproval = action != "clarify",
        )

        // Also emit progress to chat for FOREGROUND tasks
        if (task.processingMode == com.jervis.entity.ProcessingMode.FOREGROUND) {
            try {
                agentOrchestratorRpc.emitProgress(
                    clientId = task.clientId.toString(),
                    projectId = task.projectId?.toString(),
                    message = if (phase == "clarification")
                        "Orchestrátor potřebuje upřesnění" else
                        "Orchestrátor potřebuje schválení: $action",
                    metadata = mapOf("phase" to phase, "action" to action),
                )
            } catch (e: Exception) {
                logger.warn(e) { "Failed to emit interrupt notification for task ${task.id}" }
            }
        }

        logger.info { "ORCHESTRATOR_INTERRUPTED: taskId=${task.id} action=$action phase=$phase → USER_TASK" }
    }

    private suspend fun handleDone(task: TaskDocument, summary: String?) {
        // Emit final response for FOREGROUND tasks
        if (task.processingMode == com.jervis.entity.ProcessingMode.FOREGROUND) {
            val resultSummary = summary ?: "Orchestrace dokončena"
            try {
                agentOrchestratorRpc.emitToChatStream(
                    clientId = task.clientId.toString(),
                    projectId = task.projectId?.toString(),
                    response = com.jervis.dto.ChatResponseDto(resultSummary),
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to emit orchestrator result for task ${task.id}" }
            }
        }

        // Check if new user messages arrived during orchestration (inline messages)
        val hasInlineMessages = task.orchestrationStartedAt?.let { startedAt ->
            try {
                chatMessageRepository.countByTaskIdAndRoleAndTimestampAfter(
                    taskId = task.id,
                    role = com.jervis.entity.MessageRole.USER,
                    timestamp = startedAt,
                ) > 0
            } catch (e: Exception) {
                logger.warn(e) { "Failed to check for inline messages for task ${task.id}" }
                false
            }
        } ?: false

        if (hasInlineMessages && task.processingMode == com.jervis.entity.ProcessingMode.FOREGROUND) {
            // New messages arrived during orchestration — auto-requeue for processing
            val requeuedTask = task.copy(
                state = TaskStateEnum.READY_FOR_GPU,
                orchestratorThreadId = null,
                orchestrationStartedAt = null,
            )
            taskRepository.save(requeuedTask)
            logger.info { "INLINE_REQUEUE: taskId=${task.id} - new user messages arrived during orchestration, re-queuing" }
        } else {
            // Normal completion
            val updatedTask = task.copy(
                state = TaskStateEnum.DISPATCHED_GPU,
                orchestratorThreadId = null,
                orchestrationStartedAt = null,
            )
            taskRepository.save(updatedTask)

            // Background tasks: delete after completion
            if (task.processingMode == com.jervis.entity.ProcessingMode.BACKGROUND) {
                taskRepository.delete(updatedTask)
            }
        }

        logger.info { "ORCHESTRATOR_COMPLETE: taskId=${task.id} hasInlineMessages=$hasInlineMessages" }
    }

    private suspend fun handleError(task: TaskDocument, error: String?) {
        val errorMsg = error ?: "Unknown orchestrator error"
        logger.error { "ORCHESTRATOR_ERROR: taskId=${task.id} error=$errorMsg" }

        userTaskService.failAndEscalateToUserTask(
            task = task,
            reason = "PYTHON_ORCHESTRATOR_ERROR",
        )
        taskService.updateState(task, TaskStateEnum.ERROR)
    }
}
