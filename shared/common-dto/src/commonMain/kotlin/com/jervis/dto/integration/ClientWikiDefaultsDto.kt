package com.jervis.dto.integration

import kotlinx.serialization.Serializable

@Serializable
data class ClientWikiDefaultsDto(
    val clientId: String,
    val wikiSpaceKey: String?,
    val wikiRootPageId: String?,
)
