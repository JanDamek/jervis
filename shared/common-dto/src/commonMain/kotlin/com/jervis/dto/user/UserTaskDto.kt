package com.jervis.dto.user

import com.jervis.dto.AttachmentDto
import kotlinx.serialization.Serializable

@Serializable
data class UserTaskDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val state: String,
    val projectId: String? = null,
    val clientId: String,
    val sourceUri: String? = null,
    val createdAtEpochMillis: Long,
    val attachments: List<AttachmentDto> = emptyList(),
)
