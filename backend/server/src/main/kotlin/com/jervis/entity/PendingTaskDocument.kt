package com.jervis.entity

import com.jervis.domain.task.PendingTask
import com.jervis.domain.task.PendingTaskState
import com.jervis.domain.task.PendingTaskTypeEnum
import org.bson.types.ObjectId
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "pending_tasks")
@JsonIgnoreProperties(ignoreUnknown = true)
data class PendingTaskDocument(
    @Id
    val id: ObjectId = ObjectId(),
    @Indexed
    val type: String,
    val content: String,
    @Indexed
    val projectId: ObjectId? = null,
    @Indexed
    val clientId: ObjectId? = null,
    @Indexed
    val createdAt: Instant = Instant.now(),
    @Indexed
    val state: String,
    @Indexed
    val correlationId: String? = null, // For distributed tracing - nullable for backward compatibility
) {
    fun toDomain(): PendingTask =
        PendingTask(
            id = id,
            taskType = PendingTaskTypeEnum.valueOf(type),
            content = content,
            projectId = projectId,
            clientId = clientId ?: error("PendingTaskDocument $id has null clientId"),
            createdAt = createdAt,
            state = PendingTaskState.valueOf(state),
            correlationId = correlationId ?: id.toHexString(), // Fallback to taskId for old documents
        )

    companion object {
        fun fromDomain(domain: PendingTask): PendingTaskDocument =
            PendingTaskDocument(
                id = domain.id,
                type = domain.taskType.name,
                content = domain.content,
                projectId = domain.projectId,
                clientId = domain.clientId,
                createdAt = domain.createdAt,
                state = domain.state.name,
                correlationId = domain.correlationId,
            )
    }
}
