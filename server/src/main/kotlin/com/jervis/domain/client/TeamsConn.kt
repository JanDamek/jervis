package com.jervis.domain.client

data class TeamsConn(
    val tenant: String? = null,
    val scopes: List<String>? = null,
    val credentialsRef: String? = null,
)
