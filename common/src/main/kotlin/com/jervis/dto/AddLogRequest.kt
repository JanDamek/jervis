package com.jervis.dto

data class AddLogRequest(
    val projectId: String,
    val stepType: String,
    val logMessage: String,
)
