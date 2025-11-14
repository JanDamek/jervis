package com.jervis.service.background

import com.jervis.domain.task.PendingTask
import com.jervis.domain.task.PendingTaskState
import com.jervis.domain.task.PendingTaskTypeEnum
import com.jervis.entity.PendingTaskDocument
import com.jervis.repository.mongo.PendingTaskMongoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class PendingTaskService(
    private val pendingTaskRepository: PendingTaskMongoRepository,
    private val userTaskService: com.jervis.service.task.UserTaskService,
    private val debugService: com.jervis.service.debug.DebugService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun createTask(
        taskType: PendingTaskTypeEnum,
        content: String,
        projectId: ObjectId? = null,
        clientId: ObjectId,
        correlationId: String? = null,
    ): PendingTask {
        // Enforce non-empty content to avoid creating dead/empty tasks
        require(content.isNotBlank()) { "PendingTask content must be provided and non-blank" }

        val task =
            PendingTask(
                taskType = taskType,
                content = content,
                projectId = projectId,
                clientId = clientId,
                // Current pipeline does not have explicit indexing step yet; start at READY_FOR_QUALIFICATION
                state = PendingTaskState.READY_FOR_QUALIFICATION,
                correlationId = correlationId ?: java.util.UUID.randomUUID().toString(),
            )

        val document = PendingTaskDocument.fromDomain(task)
        val saved = pendingTaskRepository.save(document)

        val domainTaskForLog = saved.toDomain()
        logger.info { "TASK_CREATED: id=${saved.id} correlationId=${domainTaskForLog.correlationId} type=${taskType.name} state=${task.state} clientId=${clientId.toHexString()} projectId=${projectId?.toHexString() ?: "none"} contentLength=${content.length}" }

        // Publish debug event
        val domainTask = saved.toDomain()
        debugService.taskCreated(
            correlationId = domainTask.correlationId,
            taskId = saved.id.toHexString(),
            taskType = taskType.name,
            state = task.state.name,
            clientId = clientId.toHexString(),
            projectId = projectId?.toHexString(),
            contentLength = content.length
        )

        return saved.toDomain()
    }

    suspend fun deleteTask(taskId: ObjectId) {
        val task = pendingTaskRepository.findById(taskId)
        pendingTaskRepository.deleteById(taskId)
        logger.info { "TASK_DELETED: id=$taskId type=${task?.type ?: "unknown"} state=${task?.state ?: "unknown"}" }
    }

    fun findTasksByState(state: PendingTaskState): Flow<PendingTask> =
        pendingTaskRepository
            .findByStateOrderByCreatedAtAsc(state.name)
            .map { it.toDomain() }

    suspend fun updateState(
        taskId: ObjectId,
        expected: PendingTaskState,
        next: PendingTaskState,
    ): PendingTask {
        val task = pendingTaskRepository.findById(taskId) ?: error("Task not found: $taskId")
        require(PendingTaskState.valueOf(task.state) == expected) {
            "Invalid state transition for $taskId: expected=$expected, actual=${task.state}"
        }
        val updated = task.copy(state = next.name)
        val saved = pendingTaskRepository.save(updated)
        val domainTaskForLog = saved.toDomain()
        logger.info { "TASK_STATE_TRANSITION: id=$taskId correlationId=${domainTaskForLog.correlationId} from=$expected to=$next type=${task.type}" }

        // Publish debug event
        val domainTask = saved.toDomain()
        debugService.taskStateTransition(
            correlationId = domainTask.correlationId,
            taskId = taskId.toHexString(),
            fromState = expected.name,
            toState = next.name,
            taskType = task.type
        )

        return domainTask
    }

    suspend fun finalizeCompleted(
        taskId: ObjectId,
        from: PendingTaskState,
    ) {
        val task = pendingTaskRepository.findById(taskId) ?: return
        if (PendingTaskState.valueOf(task.state) != from) return
        logger.info { "TASK_COMPLETED_DELETE: id=$taskId type=${task.type}" }
        pendingTaskRepository.deleteById(taskId)
    }

    suspend fun failAndEscalateToUserTask(
        taskId: ObjectId,
        from: PendingTaskState,
        reason: String,
        error: String? = null,
    ): ObjectId {
        val taskDoc = pendingTaskRepository.findById(taskId) ?: error("Task not found: $taskId")
        require(PendingTaskState.valueOf(taskDoc.state) == from) { "State changed for $taskId; expected=$from actual=${taskDoc.state}" }
        val domain = taskDoc.toDomain()
        val title = "Indexing/Qualification failed: ${domain.taskType.name}"
        val description =
            buildString {
                appendLine("Pending task ${domain.id} failed in state ${domain.state}")
                appendLine("Reason: $reason")
                error?.let { appendLine("Error: $it") }
                appendLine()
                appendLine("Task Content:")
                appendLine(domain.content)
            }
        val userTask =
            userTaskService.createTask(
                title = title,
                description = description,
                projectId = domain.projectId,
                clientId = domain.clientId,
                sourceType = com.jervis.domain.task.TaskSourceType.AGENT_SUGGESTION,
                metadata =
                    mapOf(
                        "snapshot_taskType" to domain.taskType.name,
                        "snapshot_state" to domain.state.name,
                    ),
            )
        logger.info { "TASK_FAILED_ESCALATED: id=$taskId userTaskId=${userTask.id} reason=$reason" }
        pendingTaskRepository.deleteById(taskId)
        return userTask.id
    }

    fun findTasksReadyForQualification(): Flow<PendingTask> = findTasksByState(PendingTaskState.READY_FOR_QUALIFICATION)

    suspend fun tryClaimForQualification(taskId: ObjectId): PendingTask? {
        val result = runCatching { updateState(taskId, PendingTaskState.READY_FOR_QUALIFICATION, PendingTaskState.QUALIFYING) }
            .getOrNull()
        if (result != null) {
            logger.debug { "TASK_CLAIMED_FOR_QUALIFICATION: id=$taskId" }
        }
        return result
    }

    fun findAllTasks(): Flow<PendingTask> =
        pendingTaskRepository
            .findAllByOrderByCreatedAtAsc()
            .map { it.toDomain() }


    suspend fun failAndEscalateToUserTask(
        task: PendingTask,
        reason: String,
        error: Throwable? = null,
    ): ObjectId {
        val title = "Background task failed: ${task.taskType.name}"
        val description =
            buildString {
                appendLine("Pending task ${task.id} failed in state ${task.state}")
                appendLine("Reason: $reason")
                error?.message?.let { appendLine("Error: $it") }
                appendLine()
                appendLine("Task Content:")
                appendLine(task.content)
            }
        val userTask =
            userTaskService.createTask(
                title = title,
                description = description,
                projectId = task.projectId,
                clientId = task.clientId,
                sourceType = com.jervis.domain.task.TaskSourceType.AGENT_SUGGESTION,
                metadata =
                    mapOf(
                        "snapshot_taskType" to task.taskType.name,
                        "snapshot_state" to task.state.name,
                    ),
            )
        logger.info { "TASK_FAILED_ESCALATED: pending=${task.id} -> userTask=${userTask.id} reason=$reason" }
        // Delete pending task after escalation
        pendingTaskRepository.deleteById(task.id)
        return userTask.id
    }
}
