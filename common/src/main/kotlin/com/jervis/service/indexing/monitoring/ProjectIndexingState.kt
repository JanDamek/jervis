package com.jervis.service.indexing.monitoring

import java.time.Instant

/**
 * Represents the overall indexing state for a project
 */
data class ProjectIndexingState(
    val projectId: String,
    val projectName: String,
    val status: IndexingStepStatus = IndexingStepStatus.PENDING,
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val steps: MutableList<IndexingStep> = mutableListOf(),
) {
    val duration: Long?
        get() =
            if (startTime != null && endTime != null) {
                endTime.toEpochMilli() - startTime.toEpochMilli()
            } else {
                null
            }

    val overallProgress: IndexingProgress?
        get() {
            val completedSteps = steps.count { it.isCompleted }
            val totalSteps = steps.size
            return if (totalSteps > 0) {
                IndexingProgress(completedSteps, totalSteps)
            } else {
                null
            }
        }

    val isActive: Boolean
        get() = status == IndexingStepStatus.RUNNING || steps.any { it.isActive }

    val isCompleted: Boolean
        get() = status == IndexingStepStatus.COMPLETED && steps.all { it.isCompleted || it.status == IndexingStepStatus.SKIPPED }

    val hasFailed: Boolean
        get() = status == IndexingStepStatus.FAILED || steps.any { it.isFailed }
}
