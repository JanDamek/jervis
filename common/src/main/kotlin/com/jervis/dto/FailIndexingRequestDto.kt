package com.jervis.dto

data class FailIndexingRequestDto(
    val projectId: String,
    val errorMessage: String,
)
