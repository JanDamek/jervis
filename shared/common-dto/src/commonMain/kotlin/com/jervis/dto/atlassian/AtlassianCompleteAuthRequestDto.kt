package com.jervis.dto.atlassian

import kotlinx.serialization.Serializable

@Serializable
data class AtlassianCompleteAuthRequestDto(
    val clientId: String,
    val code: String,
    val verifier: String,
    val redirectUri: String,
    val correlationId: String,
    val tenant: String,
)
