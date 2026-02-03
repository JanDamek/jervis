package com.jervis.dto.bugtracker

import kotlinx.serialization.Serializable

@Serializable
data class BugTrackerCompleteAuthRequestDto(
    val clientId: String,
    val code: String,
    val verifier: String,
    val redirectUri: String,
    val correlationId: String,
    val tenant: String,
)
