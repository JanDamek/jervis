package com.jervis.domain.background

import org.bson.types.ObjectId
import java.time.Instant

data class BackgroundTask(
    val id: ObjectId = ObjectId(),
    val taskType: BackgroundTaskType,
    val targetRef: TargetRef,
    val priority: Int = 3,
    val status: BackgroundTaskStatus = BackgroundTaskStatus.PENDING,
    val checkpoint: Checkpoint? = null,
    val progress: Double = 0.0,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val labels: List<String> = emptyList(),
    val notes: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
