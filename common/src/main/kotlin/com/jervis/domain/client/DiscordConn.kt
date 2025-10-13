package com.jervis.domain.client

import kotlinx.serialization.Serializable

@Serializable
data class DiscordConn(
    val serverId: String? = null,
    val scopes: List<String>? = null,
    val credentialsRef: String? = null,
)
