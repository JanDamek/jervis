package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class DiscordConnDto(
    val serverId: String? = null,
    val scopes: List<String>? = null,
    val credentialsRef: String? = null,
)
