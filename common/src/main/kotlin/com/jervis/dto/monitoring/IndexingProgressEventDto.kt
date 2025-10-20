package com.jervis.dto.monitoring

import com.jervis.domain.IndexingStepStatusEnum
import com.jervis.domain.IndexingStepTypeEnum
import java.time.Instant

/**
 * Event published when indexing progress changes for a project
 */
data class IndexingProgressEventDto(
    val projectId: String,
    val projectName: String,
    val stepType: IndexingStepTypeEnum,
    val status: IndexingStepStatusEnum,
    val progress: IndexingProgressDto? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val timestamp: Instant = Instant.now(),
    val logs: List<String> = emptyList(),
)
