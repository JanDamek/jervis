package com.jervis.service.task

/**
 * User Task priority levels.
 */
enum class TaskPriorityEnum {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

/**
 * User Task lifecycle statuses.
 * Note: CANCELLED is not persisted (revoked tasks are deleted), but kept for completeness.
 */
enum class TaskStatusEnum {
    TODO,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
}

/**
 * Source of a user task trigger.
 */
enum class TaskSourceType {
    AGENT_SUGGESTION,
    EMAIL,
    KNOWLEDGE_APPROVAL,
    GIT_COMMIT,
    AUTHORIZATION,
}
