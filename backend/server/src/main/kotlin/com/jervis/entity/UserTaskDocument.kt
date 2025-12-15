package com.jervis.entity

import com.jervis.service.task.TaskPriorityEnum
import com.jervis.service.task.TaskSourceType
import com.jervis.service.task.TaskStatusEnum
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
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
    val priority: TaskPriorityEnum,
    @Indexed
    val status: TaskStatusEnum,
    @Indexed
    val dueDate: Instant? = null,
    val projectId: ProjectId? = null,
    val clientId: ClientId,
    val sourceType: TaskSourceType,
    val correlationId: String,
)
