package com.jervis.domain.client

import kotlinx.serialization.Serializable

@Serializable
data class Anonymization(
    val enabled: Boolean = false,
    val rules: List<String> =
        listOf(
            "(?i)Acme(\\s+Corp)? -> [CLIENT]",
            "([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+) -> [EMAIL]",
        ),
)
