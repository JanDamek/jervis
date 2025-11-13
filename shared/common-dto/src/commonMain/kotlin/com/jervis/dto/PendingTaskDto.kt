package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class PendingTaskDto(
    val id: String,
    val taskType: String,
    val content: String,
    val projectId: String?,
    val clientId: String,
    val createdAt: String,
    val state: String,
)
