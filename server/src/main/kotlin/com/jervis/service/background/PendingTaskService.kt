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
        context: Map<String, String> = emptyMap(),
    ): PendingTask {
        // Idempotency: for EMAIL_PROCESSING with sourceUri, do not create duplicates
        if (taskType == PendingTaskTypeEnum.EMAIL_PROCESSING) {
            val sourceUri = context["sourceUri"]
            if (!sourceUri.isNullOrBlank()) {
                val existing =
                    pendingTaskRepository.findFirstByClientAndTypeAndSourceUri(clientId, taskType.name, sourceUri)
                if (existing != null) {
                    logger.info { "Reusing existing pending task ${existing.id} for EMAIL_PROCESSING sourceUri=$sourceUri" }
                    return existing.toDomain()
                }
            }
        }

        val task =
            PendingTask(
                taskType = taskType,
                content = content,
                projectId = projectId,
                clientId = clientId,
                // Current pipeline does not have explicit indexing step yet; start at READY_FOR_QUALIFICATION
                state = PendingTaskState.READY_FOR_QUALIFICATION,
                context = context,
            )

        val document = PendingTaskDocument.fromDomain(task)
        val saved = pendingTaskRepository.save(document)

        logger.info { "Created pending task: ${'$'}{saved.id} - ${'$'}{taskType.name}, state=${'$'}{task.state}" }
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

    suspend fun finalizeCompleted(taskId: ObjectId, from: PendingTaskState) {
        val task = pendingTaskRepository.findById(taskId) ?: return
        if (PendingTaskState.valueOf(task.state) != from) return
        val unitId = task.context["unitId"] ?: "?"
        val contentHash = task.context["contentHash"] ?: "?"
        logger.info { "TASK_COMPLETED_DELETE: id=$taskId unit=$unitId hash=$contentHash" }
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
        val description = buildString {
            appendLine("Pending task ${domain.id} failed in state ${domain.state}")
            appendLine("Reason: $reason")
            error?.let { appendLine("Error: $it") }
        }
        val userTask =
            userTaskService.createTask(
                title = title,
                description = description,
                projectId = domain.projectId,
                clientId = domain.clientId,
                sourceType = com.jervis.domain.task.TaskSourceType.AGENT_SUGGESTION,
                sourceUri = null, // No cross-collection link to pending-task
                metadata =
                    mapOf(
                        // Snapshot without cross-collection references
                        "snapshot.taskType" to domain.taskType.name,
                        "snapshot.state" to domain.state.name,
                        "snapshot.unitId" to (domain.context["unitId"] ?: ""),
                        "snapshot.contentHash" to (domain.context["contentHash"] ?: ""),
                    ) + domain.context.mapKeys { (k, _) -> "snapshot.context.$k" },
            )
        logger.info { "TASK_FAILED_ESCALATED: id=$taskId userTaskId=${userTask.id} reason=$reason" }
        pendingTaskRepository.deleteById(taskId)
        return userTask.id
    }

    fun findTasksReadyForQualification(): Flow<PendingTask> = findTasksByState(PendingTaskState.READY_FOR_QUALIFICATION)

    suspend fun tryClaimForQualification(taskId: ObjectId): PendingTask? =
        runCatching { updateState(taskId, PendingTaskState.READY_FOR_QUALIFICATION, PendingTaskState.QUALIFYING) }
            .getOrNull()

    /**
     * Merge additional context into existing task.
     * Validates that all values are non-blank (fail fast on blank values).
     * Allowed only in NEW state.
     */
    suspend fun mergeContext(
        taskId: ObjectId,
        contextPatch: Map<String, String>,
    ): PendingTask {
        val taskDoc =
            pendingTaskRepository.findById(taskId) ?: throw IllegalArgumentException("Task not found: $taskId")
        require(taskDoc.state == PendingTaskState.NEW.name) {
            "Cannot merge context in state ${taskDoc.state} for task $taskId"
        }

        // Fail fast: reject blank values
        contextPatch.forEach { (key, value) ->
            require(value.isNotBlank()) { "Context key '$key' has blank value for task $taskId" }
        }

        val merged = taskDoc.copy(context = taskDoc.context + contextPatch)
        val saved = pendingTaskRepository.save(merged)

        logger.info { "TASK_CONTEXT_MERGE: Task ${taskId.toHexString()} merged keys ${contextPatch.keys.joinToString(", ")}" }

        return saved.toDomain()
    }

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
            }
        val userTask =
            userTaskService.createTask(
                title = title,
                description = description,
                projectId = task.projectId,
                clientId = task.clientId,
                sourceType = com.jervis.domain.task.TaskSourceType.AGENT_SUGGESTION,
                sourceUri = null, // No cross-collection link to pending-task
                metadata =
                    mapOf(
                        // Snapshot without cross-collection references
                        "snapshot.taskType" to task.taskType.name,
                        "snapshot.state" to task.state.name,
                    ) + task.context.mapKeys { (k, _) -> "snapshot.context.$k" },
            )
        logger.info { "TASK_FAILED_ESCALATED: pending=${task.id} -> userTask=${userTask.id} reason=$reason" }
        // Delete pending task after escalation
        pendingTaskRepository.deleteById(task.id)
        return userTask.id
    }
}
