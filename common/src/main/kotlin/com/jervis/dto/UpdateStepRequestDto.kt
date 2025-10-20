package com.jervis.dto

import com.jervis.dto.monitoring.IndexingProgressDto

data class UpdateStepRequestDto(
    val projectId: String,
    val stepType: String,
    val status: String,
    val progress: IndexingProgressDto?,
    val message: String?,
    val errorMessage: String?,
    val logs: List<String>?,
)
