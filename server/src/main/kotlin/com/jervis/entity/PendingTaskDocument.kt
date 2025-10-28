package com.jervis.entity

import com.jervis.domain.task.PendingTask
import com.jervis.domain.task.PendingTaskTypeEnum
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
    val content: String? = null,
    @Indexed
    val projectId: ObjectId? = null,
    @Indexed
    val clientId: ObjectId? = null,
    @Indexed
    val createdAt: Instant = Instant.now(),
    @Indexed
    val needsQualification: Boolean = false,
    val context: Map<String, String> = emptyMap(),
) {
    fun toDomain(): PendingTask =
        PendingTask(
            id = id,
            taskType = PendingTaskTypeEnum.valueOf(taskType),
            content = content,
            projectId = projectId,
            clientId = clientId,
            createdAt = createdAt,
            needsQualification = needsQualification,
            context = context,
        )

    companion object {
        fun fromDomain(domain: PendingTask): PendingTaskDocument =
            PendingTaskDocument(
                id = domain.id,
                taskType = domain.taskType.name,
                content = domain.content,
                projectId = domain.projectId,
                clientId = domain.clientId,
                createdAt = domain.createdAt,
                needsQualification = domain.needsQualification,
                context = domain.context,
            )
    }
}
