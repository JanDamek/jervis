package com.jervis.dto.bugtracker

import kotlinx.serialization.Serializable

@Serializable
data class BugTrackerApiTokenTestRequestDto(
    val tenant: String,
    val email: String,
    val apiToken: String,
)
