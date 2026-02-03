package com.jervis.dto.bugtracker

import kotlinx.serialization.Serializable

@Serializable
data class BugTrackerApiTokenSaveRequestDto(
    val clientId: String,
    val tenant: String,
    val email: String,
    val apiToken: String,
)
