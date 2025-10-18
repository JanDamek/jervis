package com.jervis.dto

import com.jervis.service.indexing.monitoring.IndexingProgress

data class UpdateStepRequest(
    val projectId: String,
    val stepType: String,
    val status: String,
    val progress: IndexingProgress?,
    val message: String?,
    val errorMessage: String?,
    val logs: List<String>?,
)
