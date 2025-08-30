package com.jervis.domain.client

data class JiraConn(
    val baseUrl: String? = null,
    val tenant: String? = null,
    val scopes: List<String>? = null,
    val credentialsRef: String? = null,
)
