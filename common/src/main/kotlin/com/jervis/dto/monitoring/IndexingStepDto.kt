package com.jervis.dto.monitoring

import com.jervis.domain.IndexingStepStatusEnum
import com.jervis.domain.IndexingStepTypeEnum
import java.time.Instant

/**
 * Represents an indexing step with its current state
 */
data class IndexingStepDto(
    val stepType: IndexingStepTypeEnum,
    val status: IndexingStepStatusEnum = IndexingStepStatusEnum.PENDING,
    val progress: IndexingProgressDto? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val logs: MutableList<String> = mutableListOf(),
    val subSteps: MutableList<IndexingStepDto> = mutableListOf(),
) {
    val duration: Long?
        get() =
            if (startTime != null && endTime != null) {
                endTime.toEpochMilli() - startTime.toEpochMilli()
            } else {
                null
            }

    val isActive: Boolean
        get() = status == IndexingStepStatusEnum.RUNNING

    val isCompleted: Boolean
        get() = status == IndexingStepStatusEnum.COMPLETED

    val isFailed: Boolean
        get() = status == IndexingStepStatusEnum.FAILED
}
