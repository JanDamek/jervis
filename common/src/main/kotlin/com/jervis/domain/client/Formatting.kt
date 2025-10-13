package com.jervis.domain.client

import kotlinx.serialization.Serializable

@Serializable
data class Formatting(
    val formatter: String = "ktlint",
    val version: String? = null,
    val lineWidth: Int = 120,
    val tabWidth: Int = 2,
    val rules: Map<String, String> = emptyMap(),
)
