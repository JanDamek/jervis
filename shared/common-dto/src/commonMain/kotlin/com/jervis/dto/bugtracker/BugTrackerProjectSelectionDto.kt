package com.jervis.dto.bugtracker

import kotlinx.serialization.Serializable

@Serializable
data class BugTrackerProjectSelectionDto(
    val clientId: String,
    val projectKey: String,
)
