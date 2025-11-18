package com.jervis.dto.atlassian

import kotlinx.serialization.Serializable

@Serializable
data class AtlassianBoardSelectionDto(
    val clientId: String,
    val boardId: Long,
)
