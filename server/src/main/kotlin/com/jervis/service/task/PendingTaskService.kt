package com.jervis.service.task

import com.jervis.domain.task.PendingTask
import com.jervis.domain.task.PendingTaskSeverity
import com.jervis.domain.task.PendingTaskStatus
import com.jervis.domain.task.PendingTaskType
import com.jervis.entity.mongo.PendingTaskDocument
import com.jervis.repository.mongo.PendingTaskMongoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class PendingTaskService(
    private val pendingTaskRepository: PendingTaskMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun createTask(
        taskType: PendingTaskType,
        severity: PendingTaskSeverity,
        title: String,
        description: String,
        context: Map<String, String>,
        errorDetails: String? = null,
        autoFixAttempted: Boolean = false,
        autoFixResult: String? = null,
        projectId: ObjectId? = null,
        clientId: ObjectId? = null,
    ): PendingTask {
        if (projectId != null) {
            val existingTask =
                pendingTaskRepository
                    .findByProjectIdAndStatusNot(projectId, PendingTaskStatus.RESOLVED.name)
                    .map { it.toDomain() }
                    .toList()
                    .firstOrNull { it.taskType == taskType && it.context == context }

            if (existingTask != null) {
                logger.info { "Duplicate pending task detected for project $projectId, skipping creation" }
                return existingTask
            }
        }

        val task =
            PendingTask(
                taskType = taskType,
                severity = severity,
                title = title,
                description = description,
                context = context,
                errorDetails = errorDetails,
                autoFixAttempted = autoFixAttempted,
                autoFixResult = autoFixResult,
                projectId = projectId,
                clientId = clientId,
                nextRetryAt = calculateNextRetry(0),
            )

        val document = PendingTaskDocument.fromDomain(task)
        val saved = pendingTaskRepository.save(document)

        logger.info { "Created pending task: ${saved.id} - $title" }
        return saved.toDomain()
    }

    suspend fun updateTask(task: PendingTask): PendingTask {
        val updated = task.copy(updatedAt = Instant.now())
        val document = PendingTaskDocument.fromDomain(updated)
        val saved = pendingTaskRepository.save(document)
        return saved.toDomain()
    }

    suspend fun markAsAnalyzing(taskId: ObjectId): PendingTask? {
        val task = pendingTaskRepository.findById(taskId)?.toDomain() ?: return null
        return updateTask(task.copy(status = PendingTaskStatus.ANALYZING))
    }

    suspend fun markAsAnalyzed(
        taskId: ObjectId,
        analysisResult: String,
        suggestedSolution: String,
    ): PendingTask? {
        val task = pendingTaskRepository.findById(taskId)?.toDomain() ?: return null
        return updateTask(
            task.copy(
                status = PendingTaskStatus.ANALYZED,
                analysisResult = analysisResult,
                suggestedSolution = suggestedSolution,
            ),
        )
    }

    suspend fun markAsResolved(
        taskId: ObjectId,
        resolution: String,
    ): PendingTask? {
        val task = pendingTaskRepository.findById(taskId)?.toDomain() ?: return null
        return updateTask(
            task.copy(
                status = PendingTaskStatus.RESOLVED,
                resolvedAt = Instant.now(),
                analysisResult = "${task.analysisResult ?: ""}\n\nResolution: $resolution",
            ),
        )
    }

    suspend fun incrementRetryCount(taskId: ObjectId): PendingTask? {
        val task = pendingTaskRepository.findById(taskId)?.toDomain() ?: return null
        val newRetryCount = task.retryCount + 1

        return if (newRetryCount >= task.maxRetries) {
            updateTask(
                task.copy(
                    retryCount = newRetryCount,
                    status = PendingTaskStatus.AWAITING_USER_INPUT,
                    nextRetryAt = null,
                ),
            )
        } else {
            updateTask(
                task.copy(
                    retryCount = newRetryCount,
                    nextRetryAt = calculateNextRetry(newRetryCount),
                ),
            )
        }
    }

    fun findOpenTasks(): Flow<PendingTask> =
        pendingTaskRepository
            .findByStatusOrderByCreatedAtDesc(PendingTaskStatus.OPEN.name)
            .map { it.toDomain() }

    fun findTasksForRetry(): Flow<PendingTask> =
        pendingTaskRepository
            .findByNextRetryAtBeforeAndStatusIn(
                Instant.now(),
                listOf(PendingTaskStatus.OPEN.name, PendingTaskStatus.ANALYZING.name),
            ).map { it.toDomain() }

    fun findTopPriorityTasks(): Flow<PendingTask> =
        pendingTaskRepository
            .findTop10ByStatusOrderBySeverityAscCreatedAtDesc(PendingTaskStatus.ANALYZED.name)
            .map { it.toDomain() }

    private fun calculateNextRetry(retryCount: Int): Instant {
        val delayMinutes =
            when (retryCount) {
                0 -> 5
                1 -> 15
                2 -> 60
                else -> 240
            }
        return Instant.now().plus(delayMinutes.toLong(), ChronoUnit.MINUTES)
    }
}
