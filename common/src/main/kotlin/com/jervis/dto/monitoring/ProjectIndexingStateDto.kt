package com.jervis.dto.monitoring

import com.jervis.domain.IndexingStepStatusEnum
import java.time.Instant

/**
 * Represents the overall indexing state for a project
 */
data class ProjectIndexingStateDto(
    val projectId: String,
    val projectName: String,
    val status: IndexingStepStatusEnum = IndexingStepStatusEnum.PENDING,
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val steps: MutableList<IndexingStepDto> = mutableListOf(),
) {
    val duration: Long?
        get() =
            if (startTime != null && endTime != null) {
                endTime.toEpochMilli() - startTime.toEpochMilli()
            } else {
                null
            }

    val overallProgress: IndexingProgressDto?
        get() {
            val completedSteps = steps.count { it.isCompleted }
            val totalSteps = steps.size
            return if (totalSteps > 0) {
                IndexingProgressDto(completedSteps, totalSteps)
            } else {
                null
            }
        }

    val isActive: Boolean
        get() = status == IndexingStepStatusEnum.RUNNING || steps.any { it.isActive }

    val isCompleted: Boolean
        get() = status == IndexingStepStatusEnum.COMPLETED && steps.all { it.isCompleted || it.status == IndexingStepStatusEnum.SKIPPED }

    val hasFailed: Boolean
        get() = status == IndexingStepStatusEnum.FAILED || steps.any { it.isFailed }
}
