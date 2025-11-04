package com.jervis.dto.user

import kotlinx.serialization.Serializable

@Serializable
data class UserTaskDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val priority: String,
    val status: String,
    val dueDateEpochMillis: Long? = null,
    val projectId: String? = null,
    val clientId: String,
    val sourceType: String,
    val sourceUri: String? = null,
    val createdAtEpochMillis: Long,
)

@Serializable
data class UserTaskCountDto(
    val clientId: String,
    val activeCount: Int,
)
