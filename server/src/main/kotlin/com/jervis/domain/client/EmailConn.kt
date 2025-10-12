package com.jervis.domain.client

data class EmailConn(
    val protocol: String? = null, // imap/graph
    val server: String? = null,
    val username: String? = null,
    val credentialsRef: String? = null,
)
