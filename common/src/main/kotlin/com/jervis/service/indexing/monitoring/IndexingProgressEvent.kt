package com.jervis.service.indexing.monitoring

import java.time.Instant

/**
 * Event published when indexing progress changes for a project
 */
data class IndexingProgressEvent(
    val projectId: String,
    val projectName: String,
    val stepType: IndexingStepType,
    val status: IndexingStepStatus,
    val progress: IndexingProgress? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val timestamp: Instant = Instant.now(),
    val logs: List<String> = emptyList(),
)
