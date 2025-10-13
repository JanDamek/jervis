package com.jervis.domain.client

import kotlinx.serialization.Serializable

@Serializable
data class SlackConn(
    val workspace: String? = null,
    val scopes: List<String>? = null,
    val credentialsRef: String? = null,
)
