package com.jervis.service.indexing.monitoring

/**
 * Represents the status of an indexing step
 */
enum class IndexingStepStatusEnum {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
}
