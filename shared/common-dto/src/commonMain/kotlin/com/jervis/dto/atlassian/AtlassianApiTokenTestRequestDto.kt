package com.jervis.dto.atlassian

import kotlinx.serialization.Serializable

@Serializable
data class AtlassianApiTokenTestRequestDto(
    val tenant: String,
    val email: String,
    val apiToken: String,
)
