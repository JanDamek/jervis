package com.jervis.common.dto

data class CodingExecuteRequest(
    val correlationId: String,
    val clientId: String,
    val projectId: String? = null,
    val taskDescription: String,
    val targetFiles: List<String> = emptyList(),
)

data class CodingExecuteResponse(
    val success: Boolean,
    val summary: String,
    val details: String? = null,
)
