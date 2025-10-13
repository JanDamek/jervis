package com.jervis.domain.client

import kotlinx.serialization.Serializable

@Serializable
data class SecretsPolicy(
    val bannedPatterns: List<String> =
        listOf(
            "AKIA[0-9A-Z]{16}",
            "xoxb-[0-9a-zA-Z-]+",
            "(?i)api[_-]?key\\s*[:=]\\s*[A-Za-z0-9-_]{20,}",
        ),
    val cloudUploadAllowed: Boolean = false,
    val allowPII: Boolean = false,
)
