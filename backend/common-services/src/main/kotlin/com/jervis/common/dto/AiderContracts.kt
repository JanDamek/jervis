package com.jervis.common.dto

data class AiderRunRequest(
    val correlationId: String,
    val clientId: String,
    val projectId: String?,
    val taskDescription: String,
    val targetFiles: List<String> = emptyList(),
    val model: String? = null,
)

data class AiderRunResponse(
    val success: Boolean,
    val output: String,
    val message: String? = null,
    val jobId: String? = null,
    val status: String? = null,
)

data class AiderStatusResponse(
    val jobId: String,
    val status: String,
    val result: String? = null,
    val error: String? = null,
)
