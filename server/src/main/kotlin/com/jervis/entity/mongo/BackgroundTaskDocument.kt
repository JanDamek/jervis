package com.jervis.entity.mongo

import com.jervis.domain.background.BackgroundTask
import com.jervis.domain.background.BackgroundTaskStatus
import com.jervis.domain.background.BackgroundTaskType
import com.jervis.domain.background.Checkpoint
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "background_tasks")
@CompoundIndexes(
    CompoundIndex(name = "status_priority_created", def = "{'status': 1, 'priority': 1, 'createdAt': 1}"),
    CompoundIndex(name = "target_type_status", def = "{'targetRef.type': 1, 'status': 1}"),
)
data class BackgroundTaskDocument(
    @Id
    val id: ObjectId = ObjectId(),
    @Indexed
    val taskType: String,
    val targetRef: String,
    val priority: Int = 3,
    @Indexed
    val status: String = BackgroundTaskStatus.PENDING.name,
    val checkpoint: String? = null,
    val progress: Double = 0.0,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val labels: List<String> = emptyList(),
    val notes: String? = null,
    val createdAt: Instant = Instant.now(),
    @Indexed
    val updatedAt: Instant = Instant.now(),
) {
    fun toDomain(): BackgroundTask =
        BackgroundTask(
            id = id,
            taskType = BackgroundTaskType.valueOf(taskType),
            targetRef = Json.decodeFromString(targetRef),
            priority = priority,
            status = BackgroundTaskStatus.valueOf(status),
            checkpoint = checkpoint?.let { Json.decodeFromString<Checkpoint>(it) },
            progress = progress,
            retryCount = retryCount,
            maxRetries = maxRetries,
            labels = labels,
            notes = notes,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    companion object {
        fun fromDomain(task: BackgroundTask): BackgroundTaskDocument =
            BackgroundTaskDocument(
                id = task.id,
                taskType = task.taskType.name,
                targetRef = Json.encodeToString(task.targetRef),
                priority = task.priority,
                status = task.status.name,
                checkpoint = task.checkpoint?.let { Json.encodeToString<Checkpoint>(it) },
                progress = task.progress,
                retryCount = task.retryCount,
                maxRetries = task.maxRetries,
                labels = task.labels,
                notes = task.notes,
                createdAt = task.createdAt,
                updatedAt = task.updatedAt,
            )
    }
}
