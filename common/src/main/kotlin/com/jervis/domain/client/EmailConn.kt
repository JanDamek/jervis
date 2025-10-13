package com.jervis.domain.client

import kotlinx.serialization.Serializable

@Serializable
data class EmailConn(
    val protocol: String? = null, // imap/graph
    val server: String? = null,
    val username: String? = null,
    val credentialsRef: String? = null,
)
