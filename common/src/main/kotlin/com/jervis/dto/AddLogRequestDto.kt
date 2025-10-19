package com.jervis.dto

data class AddLogRequestDto(
    val projectId: String,
    val stepType: String,
    val logMessage: String,
)
