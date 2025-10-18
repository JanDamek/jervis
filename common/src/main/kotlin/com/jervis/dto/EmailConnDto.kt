package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class EmailConnDto(
    val protocol: String? = null,
    val server: String? = null,
    val username: String? = null,
    val credentialsRef: String? = null,
)
