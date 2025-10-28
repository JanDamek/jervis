package com.jervis.entity

import com.jervis.domain.task.TaskPriority
import com.jervis.domain.task.TaskSourceType
import com.jervis.domain.task.TaskStatus
import com.jervis.domain.task.UserTask
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "user_tasks")
data class UserTaskDocument(
    @Id
    val id: ObjectId = ObjectId(),
    val title: String,
    val description: String? = null,
    val priority: String,
    @Indexed
    val status: String,
    @Indexed
    val dueDate: Instant? = null,
    @Indexed
    val projectId: ObjectId? = null,
    @Indexed
    val clientId: ObjectId,
    val sourceType: String,
    val sourceUri: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    @Indexed
    val createdAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
) {
    fun toDomain(): UserTask =
        UserTask(
            id = id,
            title = title,
            description = description,
            priority = TaskPriority.valueOf(priority),
            status = TaskStatus.valueOf(status),
            dueDate = dueDate,
            projectId = projectId,
            clientId = clientId,
            sourceType = TaskSourceType.valueOf(sourceType),
            sourceUri = sourceUri,
            metadata = metadata,
            createdAt = createdAt,
            completedAt = completedAt,
        )

    companion object {
        fun fromDomain(domain: UserTask): UserTaskDocument =
            UserTaskDocument(
                id = domain.id,
                title = domain.title,
                description = domain.description,
                priority = domain.priority.name,
                status = domain.status.name,
                dueDate = domain.dueDate,
                projectId = domain.projectId,
                clientId = domain.clientId,
                sourceType = domain.sourceType.name,
                sourceUri = domain.sourceUri,
                metadata = domain.metadata,
                createdAt = domain.createdAt,
                completedAt = domain.completedAt,
            )
    }
}
