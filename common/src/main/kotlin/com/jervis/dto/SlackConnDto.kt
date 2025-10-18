package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class SlackConnDto(
    val workspace: String? = null,
    val scopes: List<String>? = null,
    val credentialsRef: String? = null,
)
