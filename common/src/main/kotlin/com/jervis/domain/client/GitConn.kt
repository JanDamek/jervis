package com.jervis.domain.client

import kotlinx.serialization.Serializable

@Serializable
data class GitConn(
    val provider: String? = null,
    val baseUrl: String? = null,
    val authType: String? = null,
    val credentialsRef: String? = null,
)
