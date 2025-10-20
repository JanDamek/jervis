package com.jervis.entity.mongo

import com.jervis.domain.task.PendingTask
import com.jervis.domain.task.PendingTaskSeverity
import com.jervis.domain.task.PendingTaskStatus
import com.jervis.domain.task.PendingTaskType
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "pending_tasks")
data class PendingTaskDocument(
    @Id
    val id: ObjectId = ObjectId(),
    @Indexed
    val taskType: String,
    @Indexed
    val severity: String,
    val title: String,
    val description: String,
    val context: Map<String, String>,
    val errorDetails: String?,
    val autoFixAttempted: Boolean = false,
    val autoFixResult: String? = null,
    @Indexed
    val status: String,
    val analysisResult: String? = null,
    val suggestedSolution: String? = null,
    @Indexed
    val projectId: ObjectId? = null,
    @Indexed
    val clientId: ObjectId? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    @Indexed
    val nextRetryAt: Instant? = null,
    @Indexed
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val resolvedAt: Instant? = null,
) {
    fun toDomain(): PendingTask =
        PendingTask(
            id = id,
            taskType = PendingTaskType.valueOf(taskType),
            severity = PendingTaskSeverity.valueOf(severity),
            title = title,
            description = description,
            context = context,
            errorDetails = errorDetails,
            autoFixAttempted = autoFixAttempted,
            autoFixResult = autoFixResult,
            status = PendingTaskStatus.valueOf(status),
            analysisResult = analysisResult,
            suggestedSolution = suggestedSolution,
            projectId = projectId,
            clientId = clientId,
            retryCount = retryCount,
            maxRetries = maxRetries,
            nextRetryAt = nextRetryAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
            resolvedAt = resolvedAt,
        )

    companion object {
        fun fromDomain(domain: PendingTask): PendingTaskDocument =
            PendingTaskDocument(
                id = domain.id,
                taskType = domain.taskType.name,
                severity = domain.severity.name,
                title = domain.title,
                description = domain.description,
                context = domain.context,
                errorDetails = domain.errorDetails,
                autoFixAttempted = domain.autoFixAttempted,
                autoFixResult = domain.autoFixResult,
                status = domain.status.name,
                analysisResult = domain.analysisResult,
                suggestedSolution = domain.suggestedSolution,
                projectId = domain.projectId,
                clientId = domain.clientId,
                retryCount = domain.retryCount,
                maxRetries = domain.maxRetries,
                nextRetryAt = domain.nextRetryAt,
                createdAt = domain.createdAt,
                updatedAt = domain.updatedAt,
                resolvedAt = domain.resolvedAt,
            )
    }
}
