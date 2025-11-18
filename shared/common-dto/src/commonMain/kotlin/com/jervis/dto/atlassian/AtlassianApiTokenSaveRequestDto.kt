package com.jervis.dto.atlassian

import kotlinx.serialization.Serializable

@Serializable
data class AtlassianApiTokenSaveRequestDto(
    val clientId: String,
    val tenant: String,
    val email: String,
    val apiToken: String,
)
