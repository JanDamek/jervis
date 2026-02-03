package com.jervis.dto.atlassian

import kotlinx.serialization.Serializable

@Serializable
data class AtlassianBoardRefDto(
    val id: Long,
    val name: String,
    val type: String? = null,
)
