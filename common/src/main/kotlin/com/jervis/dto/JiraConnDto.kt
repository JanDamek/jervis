package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class JiraConnDto(
    val baseUrl: String? = null,
    val tenant: String? = null,
    val scopes: List<String>? = null,
    val credentialsRef: String? = null,
)
