package com.jervis.service.indexing.monitoring

import java.time.Instant

/**
 * Represents an indexing step with its current state
 */
data class IndexingStep(
    val stepType: IndexingStepType,
    val status: IndexingStepStatus = IndexingStepStatus.PENDING,
    val progress: IndexingProgress? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val logs: MutableList<String> = mutableListOf(),
    val subSteps: MutableList<IndexingStep> = mutableListOf(),
) {
    val duration: Long?
        get() =
            if (startTime != null && endTime != null) {
                endTime.toEpochMilli() - startTime.toEpochMilli()
            } else {
                null
            }

    val isActive: Boolean
        get() = status == IndexingStepStatus.RUNNING

    val isCompleted: Boolean
        get() = status == IndexingStepStatus.COMPLETED

    val isFailed: Boolean
        get() = status == IndexingStepStatus.FAILED
}
