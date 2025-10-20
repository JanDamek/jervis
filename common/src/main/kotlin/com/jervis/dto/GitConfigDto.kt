package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class GitConfigDto(
    val gitUserName: String? = null,
    val gitUserEmail: String? = null,
    val commitMessageTemplate: String? = null,
    val requireGpgSign: Boolean = false,
    val gpgKeyId: String? = null,
    val requireLinearHistory: Boolean = false,
    val conventionalCommits: Boolean = false,
    val commitRules: Map<String, String> = emptyMap(),
)
