package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class PendingTaskDto(
    val id: String,
    val taskType: String,
    val content: String,
    // Optional: some tasks are client-scoped only and do not belong to a specific project
    val projectId: String? = null,
    val clientId: String,
    val createdAt: String,
    val state: String,
    val attachments: List<AttachmentDto> = emptyList(),
)
