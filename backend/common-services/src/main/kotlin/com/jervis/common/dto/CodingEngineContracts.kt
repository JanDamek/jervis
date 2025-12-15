package com.jervis.common.dto

data class CodingExecuteRequest(
    val engine: String, // e.g., "aider" or "openhands"
    val correlationId: String,
    val clientId: String,
    val projectId: String? = null,
    val taskDescription: String,
    val targetFiles: List<String> = emptyList(),
    val extra: Map<String, String> = emptyMap(),
)

data class CodingExecuteResponse(
    val success: Boolean,
    val engine: String,
    val summary: String,
    val details: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)
