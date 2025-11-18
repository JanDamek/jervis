package com.jervis.dto.atlassian

import kotlinx.serialization.Serializable

@Serializable
data class AtlassianUserSelectionDto(
    val clientId: String,
    val accountId: String,
)
