package com.jervis.domain.task

import kotlinx.serialization.Serializable

@Serializable
enum class ScheduledTaskStatusEnum {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
