package com.jervis.domain.task

import kotlinx.serialization.Serializable

@Serializable
enum class ScheduledTaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
