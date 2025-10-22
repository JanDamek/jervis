package com.jervis.dto.monitoring

import com.jervis.domain.IndexingStepStatusEnum
import com.jervis.domain.IndexingStepTypeEnum
import kotlinx.serialization.Serializable

/**
 * Event published when indexing progress changes for a project
 */
@Serializable
data class IndexingProgressEventDto(
    val eventType: String = "INDEXING_PROGRESS",
    val projectId: String,
    val projectName: String,
    val stepType: IndexingStepTypeEnum,
    val status: IndexingStepStatusEnum,
    val progress: IndexingProgressDto? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val timestamp: String,
    val logs: List<String> = emptyList(),
)
