package com.jervis.dto.jira

import kotlinx.serialization.Serializable

@Serializable
data class JiraBoardRefDto(
    val id: Long,
    val name: String,
)
