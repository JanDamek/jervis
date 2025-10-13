package com.jervis.domain.client

import kotlinx.serialization.Serializable

@Serializable
data class JiraConn(
    val baseUrl: String? = null,
    val tenant: String? = null,
    val scopes: List<String>? = null,
    val credentialsRef: String? = null,
)
