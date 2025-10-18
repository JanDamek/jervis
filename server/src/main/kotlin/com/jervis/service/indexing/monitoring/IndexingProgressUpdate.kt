package com.jervis.service.indexing.monitoring

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Internal server-side event published when indexing progress changes for a project.
 * This uses domain types (ObjectId) and must not leak outside service layer.
 */
data class IndexingProgressUpdate(
    val projectId: ObjectId,
    val projectName: String,
    val stepType: IndexingStepType,
    val status: IndexingStepStatus,
    val progress: IndexingProgress? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val timestamp: Instant = Instant.now(),
    val logs: List<String> = emptyList(),
)
