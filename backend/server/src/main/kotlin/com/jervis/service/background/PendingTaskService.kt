package com.jervis.service.background

import com.jervis.dto.PendingTaskStateEnum
import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.entity.PendingTaskDocument
import com.jervis.repository.PendingTaskMongoRepository
import com.jervis.service.task.TaskSourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.util.UUID

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
    ): PendingTaskDocument {
        require(content.isNotBlank()) { "PendingTask content must be provided and non-blank" }

        val task =
            PendingTaskDocument(
                type = taskType,
                content = content,
                projectId = projectId,
                clientId = clientId,
                state = PendingTaskStateEnum.READY_FOR_QUALIFICATION,
                correlationId = correlationId ?: UUID.randomUUID().toString(),
            )

        val saved = pendingTaskRepository.save(task)

        logger.info {
            "TASK_CREATED: id=${saved.id} correlationId=${saved.correlationId} type=${taskType.name} state=${task.state} clientId=${clientId.toHexString()} projectId=${projectId?.toHexString() ?: "none"} contentLength=${content.length}"
        }

        // Publish debug event
        debugService.taskCreated(
            correlationId = saved.correlationId,
            taskId = saved.id.toHexString(),
            taskType = taskType.name,
            state = task.state.name,
            clientId = clientId.toHexString(),
            projectId = projectId?.toHexString(),
            contentLength = content.length,
        )

        return saved
    }

    suspend fun deleteTask(taskId: ObjectId) {
        val task = pendingTaskRepository.findById(taskId)
        pendingTaskRepository.deleteById(taskId)
        logger.info { "TASK_DELETED: id=$taskId type=${task?.type ?: "unknown"} state=${task?.state ?: "unknown"}" }
    }

    fun findTasksByState(state: PendingTaskStateEnum): Flow<PendingTaskDocument> =
        pendingTaskRepository.findByStateOrderByCreatedAtAsc(state)

    suspend fun updateState(
        taskId: ObjectId,
        expected: PendingTaskStateEnum,
        next: PendingTaskStateEnum,
    ): PendingTaskDocument {
        val task = pendingTaskRepository.findById(taskId) ?: error("Task not found: $taskId")
        require(task.state == expected) {
            "Invalid state transition for $taskId: expected=$expected, actual=${task.state}"
        }
        val updated = task.copy(state = next)
        val saved = pendingTaskRepository.save(updated)
        logger.info {
            "TASK_STATE_TRANSITION: id=$taskId correlationId=${saved.correlationId} from=$expected to=$next type=${task.type}"
        }

        // Publish debug event
        debugService.taskStateTransition(
            correlationId = saved.correlationId,
            taskId = taskId.toHexString(),
            fromState = expected.name,
            toState = next.name,
            taskType = task.type,
        )

        return saved
    }

    /**
     * Append progress context to task content for interrupted task resumption.
     * The task will resume with all previous progress included in content.
     */
    suspend fun appendProgressContext(
        taskId: ObjectId,
        progressContext: String,
    ) {
        val task = pendingTaskRepository.findById(taskId) ?: error("Task not found: $taskId")

        val updatedContent = task.content + progressContext

        val updated = task.copy(content = updatedContent)
        pendingTaskRepository.save(updated)

        logger.info {
            "TASK_PROGRESS_APPENDED: id=$taskId added ${progressContext.length} chars, " +
                "total content now ${updatedContent.length} chars"
        }
    }

    /**
     * Return all tasks that should be considered for qualification now:
     * - READY_FOR_QUALIFICATION: need to be claimed
     * - QUALIFYING: tasks that might have been claimed previously (e.g., before restart) and should continue
     */
    fun findTasksForQualification(): Flow<PendingTaskDocument> =
        merge(
            findTasksByState(PendingTaskStateEnum.READY_FOR_QUALIFICATION),
            findTasksByState(PendingTaskStateEnum.QUALIFYING),
        )

    suspend fun tryClaimForQualification(taskId: ObjectId): PendingTaskDocument? {
        val result =
            runCatching { updateState(taskId, PendingTaskStateEnum.READY_FOR_QUALIFICATION, PendingTaskStateEnum.QUALIFYING) }
                .getOrNull()
        if (result != null) {
            logger.debug { "TASK_CLAIMED_FOR_QUALIFICATION: id=$taskId" }
        }
        return result
    }

    fun findAllTasks(
        taskType: PendingTaskTypeEnum?,
        state: PendingTaskStateEnum?,
    ): Flow<PendingTaskDocument> {
        val tasks =
            when {
                taskType != null && state != null -> pendingTaskRepository.findByTypeAndStateOrderByCreatedAtAsc(taskType, state)
                taskType != null -> pendingTaskRepository.findByTypeOrderByCreatedAtAsc(taskType)
                state != null -> pendingTaskRepository.findByStateOrderByCreatedAtAsc(state)
                else -> pendingTaskRepository.findAllByOrderByCreatedAtAsc()
            }
        return tasks
    }

    suspend fun countTasks(
        taskType: PendingTaskTypeEnum?,
        state: PendingTaskStateEnum?,
    ): Long =
        when {
            taskType != null && state != null -> pendingTaskRepository.countByTypeAndState(taskType, state)
            taskType != null -> pendingTaskRepository.countByType(taskType)
            state != null -> pendingTaskRepository.countByState(state)
            else -> pendingTaskRepository.count()
        }

    suspend fun failAndEscalateToUserTask(
        task: PendingTaskDocument,
        reason: String,
        error: Throwable? = null,
    ): ObjectId {
        val title = "Background task failed: ${task.type}"
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
                sourceType = TaskSourceType.AGENT_SUGGESTION,
                correlationId = task.correlationId,
            )
        logger.info { "TASK_FAILED_ESCALATED: pending=${task.id} -> userTask=${userTask.id} reason=$reason" }
        // Delete pending task after escalation
        pendingTaskRepository.deleteById(task.id)
        return userTask.id
    }
}
