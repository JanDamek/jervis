package com.jervis.dto.integration

import kotlinx.serialization.Serializable

@Serializable
data class ClientConfluenceDefaultsDto(
    val clientId: String,
    val confluenceSpaceKey: String?,
    val confluenceRootPageId: String?,
)
