package com.jervis.dto.atlassian

import kotlinx.serialization.Serializable

@Serializable
data class AtlassianBeginAuthRequestDto(
    val clientId: String,
    val redirectUri: String,
    val tenant: String, // Atlassian Cloud base host, e.g. your-domain.atlassian.net
)
