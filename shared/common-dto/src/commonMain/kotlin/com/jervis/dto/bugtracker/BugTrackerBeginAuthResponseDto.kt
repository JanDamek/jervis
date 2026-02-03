package com.jervis.dto.bugtracker

import kotlinx.serialization.Serializable

@Serializable
data class BugTrackerBeginAuthResponseDto(
    val correlationId: String,
    val authUrl: String,
)
