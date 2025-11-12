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
) {
    private val logger = KotlinLogging.logger {}

    suspend fun createTask(
        taskType: PendingTaskTypeEnum,
        content: String? = null,
        projectId: ObjectId? = null,
        clientId: ObjectId,
        sourceUri: String? = null,
    ): PendingTask {
        // Idempotency: for selected types with canonical sourceUri, do not create duplicates
        val dedupeBySourceUriTypes =
            setOf(
                PendingTaskTypeEnum.EMAIL_PROCESSING,
                PendingTaskTypeEnum.CONFLUENCE_PAGE_ANALYSIS,
                PendingTaskTypeEnum.COMMIT_ANALYSIS,
                PendingTaskTypeEnum.FILE_STRUCTURE_ANALYSIS,
                PendingTaskTypeEnum.PROJECT_DESCRIPTION_UPDATE,
            )
        if (taskType in dedupeBySourceUriTypes && !sourceUri.isNullOrBlank()) {
            val existing =
                pendingTaskRepository.findFirstByClientAndTypeAndSourceUri(clientId, taskType.name, sourceUri)
            if (existing != null) {
                logger.info { "Reusing existing pending task ${existing.id} for ${taskType.name} sourceUri=$sourceUri" }
                return existing.toDomain()
            }
        }

        val task =
            PendingTask(
                taskType = taskType,
                content = content,
                projectId = projectId,
                clientId = clientId,
                sourceUri = sourceUri,
                // Current pipeline does not have explicit indexing step yet; start at READY_FOR_QUALIFICATION
                state = PendingTaskState.READY_FOR_QUALIFICATION,
            )

        val document = PendingTaskDocument.fromDomain(task)
        val saved = pendingTaskRepository.save(document)

        logger.info { "Created pending task: ${saved.id} - ${taskType.name}, state=${task.state}" }
        return saved.toDomain()
    }

    suspend fun deleteTask(taskId: ObjectId) {
        pendingTaskRepository.deleteById(taskId)
        logger.info { "Deleted pending task: $taskId" }
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
        logger.info { "TASK_STATE_CHANGE: id=$taskId from=$expected to=$next" }
        return saved.toDomain()
    }

    suspend fun finalizeCompleted(
        taskId: ObjectId,
        from: PendingTaskState,
    ) {
        val task = pendingTaskRepository.findById(taskId) ?: return
        if (PendingTaskState.valueOf(task.state) != from) return
        logger.info { "TASK_COMPLETED_DELETE: id=$taskId type=${task.type} sourceUri=${task.sourceUri}" }
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
                appendLine(domain.content?.take(500) ?: "(no content)")
            }
        val userTask =
            userTaskService.createTask(
                title = title,
                description = description,
                projectId = domain.projectId,
                clientId = domain.clientId,
                sourceType = com.jervis.domain.task.TaskSourceType.AGENT_SUGGESTION,
                sourceUri = domain.sourceUri,
                metadata =
                    mapOf(
                        "snapshot_taskType" to domain.taskType.name,
                        "snapshot_state" to domain.state.name,
                        "snapshot_sourceUri" to (domain.sourceUri ?: ""),
                    ),
            )
        logger.info { "TASK_FAILED_ESCALATED: id=$taskId userTaskId=${userTask.id} reason=$reason" }
        pendingTaskRepository.deleteById(taskId)
        return userTask.id
    }

    fun findTasksReadyForQualification(): Flow<PendingTask> = findTasksByState(PendingTaskState.READY_FOR_QUALIFICATION)

    suspend fun tryClaimForQualification(taskId: ObjectId): PendingTask? =
        runCatching { updateState(taskId, PendingTaskState.READY_FOR_QUALIFICATION, PendingTaskState.QUALIFYING) }
            .getOrNull()


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
                appendLine(task.content?.take(500) ?: "(no content)")
            }
        val userTask =
            userTaskService.createTask(
                title = title,
                description = description,
                projectId = task.projectId,
                clientId = task.clientId,
                sourceType = com.jervis.domain.task.TaskSourceType.AGENT_SUGGESTION,
                sourceUri = task.sourceUri,
                metadata =
                    mapOf(
                        "snapshot_taskType" to task.taskType.name,
                        "snapshot_state" to task.state.name,
                        "snapshot_sourceUri" to (task.sourceUri ?: ""),
                    ),
            )
        logger.info { "TASK_FAILED_ESCALATED: pending=${task.id} -> userTask=${userTask.id} reason=$reason" }
        // Delete pending task after escalation
        pendingTaskRepository.deleteById(task.id)
        return userTask.id
    }
}
