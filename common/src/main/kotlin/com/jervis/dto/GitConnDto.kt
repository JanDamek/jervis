package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class GitConnDto(
    val provider: String? = null,
    val baseUrl: String? = null,
    val authType: String? = null,
    val credentialsRef: String? = null,
)
