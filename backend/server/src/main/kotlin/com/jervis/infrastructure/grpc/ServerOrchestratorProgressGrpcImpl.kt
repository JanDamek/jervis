package com.jervis.infrastructure.grpc

import com.jervis.agent.OrchestratorStatusHandler
import com.jervis.agent.OrchestratorWorkflowTracker
import com.jervis.chat.ChatRpcImpl
import com.jervis.common.types.TaskId
import com.jervis.contracts.server.AckResponse
import com.jervis.contracts.server.CorrectionProgressRequest
import com.jervis.contracts.server.MemoryGraphChangedRequest
import com.jervis.contracts.server.OrchestratorProgressRequest
import com.jervis.contracts.server.OrchestratorStatusRequest
import com.jervis.contracts.server.QualificationDoneRequest
import com.jervis.contracts.server.ServerOrchestratorProgressServiceGrpcKt
import com.jervis.contracts.server.ThinkingGraphUpdateRequest
import com.jervis.dto.task.TaskStateEnum
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.rpc.SubTaskRequest
import com.jervis.task.OrchestratorStepRecord
import com.jervis.task.PendingTaskService
import com.jervis.task.TaskRepository
import com.jervis.task.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ServerOrchestratorProgressGrpcImpl(
    private val taskService: TaskService,
    private val taskRepository: TaskRepository,
    private val pendingTaskService: PendingTaskService,
    private val notificationRpc: NotificationRpcImpl,
    private val chatRpcImpl: ChatRpcImpl,
    private val orchestratorWorkflowTracker: OrchestratorWorkflowTracker,
    private val orchestratorStatusHandler: OrchestratorStatusHandler,
) : ServerOrchestratorProgressServiceGrpcKt.ServerOrchestratorProgressServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun orchestratorProgress(
        request: OrchestratorProgressRequest,
    ): AckResponse {
        try {
            orchestratorWorkflowTracker.addStep(
                taskId = request.taskId,
                node = request.node,
                tools = emptyList(),
            )
            backgroundScope.launch {
                try {
                    val taskId = TaskId(ObjectId(request.taskId))
                    taskService.appendOrchestratorStep(
                        taskId,
                        OrchestratorStepRecord(
                            timestamp = Instant.now(),
                            node = request.node,
                            message = request.message,
                            goalIndex = request.goalIndex,
                            totalGoals = request.totalGoals,
                            stepIndex = request.stepIndex,
                            totalSteps = request.totalSteps,
                        ),
                    )
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to persist orchestrator step for task ${request.taskId}" }
                }
                notificationRpc.emitOrchestratorTaskProgress(
                    taskId = request.taskId,
                    clientId = request.clientId,
                    node = request.node,
                    message = request.message,
                    percent = request.percent,
                    goalIndex = request.goalIndex,
                    totalGoals = request.totalGoals,
                    stepIndex = request.stepIndex,
                    totalSteps = request.totalSteps,
                )
            }
            return AckResponse.newBuilder().setOk(true).build()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to process orchestrator progress" }
            return AckResponse.newBuilder().setOk(false).setError(e.message.orEmpty()).build()
        }
    }

    override suspend fun orchestratorStatus(
        request: OrchestratorStatusRequest,
    ): AckResponse {
        return try {
            backgroundScope.launch {
                notificationRpc.emitOrchestratorTaskStatusChange(
                    taskId = request.taskId,
                    clientId = request.clientId,
                    threadId = request.threadId,
                    status = request.status,
                    summary = request.summary.takeIf { it.isNotBlank() },
                    error = request.error.takeIf { it.isNotBlank() },
                    interruptAction = request.interruptAction.takeIf { it.isNotBlank() },
                    interruptDescription = request.interruptDescription.takeIf { it.isNotBlank() },
                    branch = request.branch.takeIf { it.isNotBlank() },
                    artifacts = request.artifactsList,
                )
                orchestratorStatusHandler.handleStatusChange(
                    taskId = request.taskId,
                    status = request.status,
                    summary = request.summary.takeIf { it.isNotBlank() },
                    error = request.error.takeIf { it.isNotBlank() },
                    interruptAction = request.interruptAction.takeIf { it.isNotBlank() },
                    interruptDescription = request.interruptDescription.takeIf { it.isNotBlank() },
                    branch = request.branch.takeIf { it.isNotBlank() },
                    artifacts = request.artifactsList,
                    keepEnvironmentRunning = request.keepEnvironmentRunning,
                )
            }
            AckResponse.newBuilder().setOk(true).build()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to process orchestrator status" }
            AckResponse.newBuilder().setOk(false).setError(e.message.orEmpty()).build()
        }
    }

    override suspend fun qualificationDone(request: QualificationDoneRequest): AckResponse {
        logger.info {
            "QUALIFICATION_DONE: taskId=${request.taskId} decision=${request.decision} priority=${request.priorityScore}"
        }
        backgroundScope.launch {
            try {
                val taskId = TaskId(ObjectId(request.taskId))
                val task = taskRepository.getById(taskId) ?: run {
                    logger.error { "QUALIFICATION_DONE: task not found taskId=${request.taskId}" }
                    return@launch
                }
                taskService.saveQualificationResult(
                    taskId = taskId,
                    priorityScore = request.priorityScore,
                    priorityReason = request.reason,
                    actionType = request.actionType,
                    estimatedComplexity = request.estimatedComplexity,
                    qualifierContext = request.contextSummary + "\n\n" + request.suggestedApproach,
                )
                val qualSummary = request.contextSummary.takeIf { it.isNotBlank() }
                if (qualSummary != null) {
                    taskService.saveSummary(taskId, qualSummary)
                }
                taskService.clearNeedsQualification(taskId)

                when (request.decision) {
                    "DONE" -> {
                        taskService.updateState(task, TaskStateEnum.DONE)
                        logger.info { "QUALIFICATION_DONE: taskId=${request.taskId} → DONE (${request.reason})" }
                    }
                    "ESCALATE" -> {
                        val pending = request.pendingUserQuestion.takeIf { it.isNotBlank() } ?: request.reason
                        val ctxQ = request.userQuestionContext.takeIf { it.isNotBlank() } ?: request.contextSummary
                        taskService.transitionToUserTask(
                            task = task,
                            pendingQuestion = pending,
                            questionContext = ctxQ,
                        )
                        logger.info { "QUALIFICATION_DONE: taskId=${request.taskId} → USER_TASK (ESCALATE)" }
                    }
                    "DECOMPOSE" -> {
                        if (request.subTasksList.isEmpty()) {
                            logger.warn {
                                "QUALIFICATION_DONE: DECOMPOSE with empty sub_tasks taskId=${request.taskId}, falling back to QUEUED"
                            }
                            taskService.updateState(task, TaskStateEnum.QUEUED)
                        } else {
                            val subTasks = request.subTasksList.map { sub ->
                                SubTaskRequest(
                                    taskName = sub.taskName,
                                    content = sub.content,
                                    phase = sub.phase.takeIf { it.isNotBlank() },
                                    orderInPhase = sub.orderInPhase,
                                )
                            }
                            taskService.decomposeTask(task, subTasks)
                            logger.info {
                                "QUALIFICATION_DONE: taskId=${request.taskId} → BLOCKED (DECOMPOSE: ${subTasks.size})"
                            }
                        }
                    }
                    "REOPEN" -> {
                        val targetId = request.targetTaskId.takeIf { it.isNotBlank() }
                        if (targetId == null) {
                            logger.warn {
                                "QUALIFICATION_DONE: REOPEN with no target_task_id taskId=${request.taskId}, falling back to QUEUED"
                            }
                            taskService.updateState(task, TaskStateEnum.QUEUED)
                        } else {
                            pendingTaskService.reopen(targetId, "Reopened by qualifier: ${request.reason}")
                            taskService.updateState(task, TaskStateEnum.DONE)
                            logger.info { "QUALIFICATION_DONE: taskId=${request.taskId} → REOPEN target=$targetId" }
                        }
                    }
                    "URGENT_ALERT" -> {
                        taskService.updateState(task, TaskStateEnum.QUEUED)
                        if (chatRpcImpl.isUserOnline()) {
                            try {
                                chatRpcImpl.pushUrgentAlert(
                                    sourceUrn = task.sourceUrn.value,
                                    taskId = task.id.toString(),
                                    taskName = task.taskName,
                                    summary = request.alertMessage.takeIf { it.isNotBlank() } ?: request.reason,
                                    suggestedAction = null,
                                    taskContent = task.content,
                                )
                            } catch (e: Exception) {
                                logger.warn(e) { "QUALIFICATION_DONE: failed to push urgent alert" }
                            }
                        }
                        logger.info { "QUALIFICATION_DONE: taskId=${request.taskId} → QUEUED (URGENT)" }
                    }
                    else -> {
                        taskService.updateState(task, TaskStateEnum.QUEUED)
                        logger.info { "QUALIFICATION_DONE: taskId=${request.taskId} → QUEUED (priority=${request.priorityScore})" }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "QUALIFICATION_DONE: failed to process taskId=${request.taskId}" }
            }
        }
        return AckResponse.newBuilder().setOk(true).build()
    }

    override suspend fun memoryGraphChanged(request: MemoryGraphChangedRequest): AckResponse {
        // Memory Graph removed — accept the RPC for backward compatibility
        // with in-flight Python callers, but do not broadcast anything.
        return AckResponse.newBuilder().setOk(true).build()
    }

    override suspend fun thinkingGraphUpdate(request: ThinkingGraphUpdateRequest): AckResponse {
        return try {
            backgroundScope.launch {
                chatRpcImpl.pushThinkingGraphUpdate(
                    taskId = request.taskId,
                    taskTitle = request.taskTitle,
                    graphId = request.graphId,
                    status = request.status,
                    message = request.message,
                    metadata = request.metadataMap,
                )
            }
            AckResponse.newBuilder().setOk(true).build()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to process thinking graph update" }
            AckResponse.newBuilder().setOk(false).setError(e.message.orEmpty()).build()
        }
    }

    override suspend fun correctionProgress(request: CorrectionProgressRequest): AckResponse {
        return try {
            backgroundScope.launch {
                notificationRpc.emitMeetingCorrectionProgress(
                    meetingId = request.meetingId,
                    clientId = request.clientId,
                    percent = request.percent,
                    chunksDone = request.chunksDone,
                    totalChunks = request.totalChunks,
                    message = request.message.takeIf { it.isNotBlank() },
                    tokensGenerated = request.tokensGenerated,
                )
            }
            AckResponse.newBuilder().setOk(true).build()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to process correction progress" }
            AckResponse.newBuilder().setOk(false).setError(e.message.orEmpty()).build()
        }
    }
}
