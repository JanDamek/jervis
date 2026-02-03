package com.jervis.dto.bugtracker

import kotlinx.serialization.Serializable

@Serializable
data class BugTrackerBeginAuthRequestDto(
    val clientId: String,
    val redirectUri: String,
    val tenant: String,
)
