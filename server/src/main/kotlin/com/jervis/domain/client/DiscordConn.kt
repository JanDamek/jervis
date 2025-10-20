package com.jervis.domain.client

data class DiscordConn(
    val serverId: String? = null,
    val scopes: List<String>? = null,
    val credentialsRef: String? = null,
)
