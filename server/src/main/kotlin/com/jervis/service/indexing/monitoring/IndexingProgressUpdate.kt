package com.jervis.service.indexing.monitoring

import com.jervis.domain.IndexingStepStatusEnum
import com.jervis.domain.IndexingStepTypeEnum
import com.jervis.dto.monitoring.IndexingProgressDto
import org.bson.types.ObjectId
import java.time.Instant

/**
 * Internal server-side event published when indexing progress changes for a project.
 * This uses domain types (ObjectId) and must not leak outside service layer.
 */
data class IndexingProgressUpdate(
    val projectId: ObjectId,
    val projectName: String,
    val stepType: IndexingStepTypeEnum,
    val status: IndexingStepStatusEnum,
    val progress: IndexingProgressDto? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val timestamp: Instant = Instant.now(),
    val logs: List<String> = emptyList(),
)
