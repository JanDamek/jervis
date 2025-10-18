package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class TeamsConnDto(
    val tenant: String? = null,
    val scopes: List<String>? = null,
    val credentialsRef: String? = null,
)
