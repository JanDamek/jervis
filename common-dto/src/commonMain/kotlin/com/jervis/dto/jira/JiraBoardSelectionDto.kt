package com.jervis.dto.jira

import kotlinx.serialization.Serializable

@Serializable
data class JiraBoardSelectionDto(
    val clientId: String,
    val boardId: Long,
)
