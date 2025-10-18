package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class AnonymizationDto(
    val enabled: Boolean = false,
    val rules: List<String> =
        listOf(
            "(?i)Acme(\\s+Corp)? -> [CLIENT]",
            "([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+) -> [EMAIL]",
        ),
)
