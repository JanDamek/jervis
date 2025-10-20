package com.jervis.domain.client

data class SlackConn(
    val workspace: String? = null,
    val scopes: List<String>? = null,
    val credentialsRef: String? = null,
)
