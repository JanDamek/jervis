package com.jervis.dto.bugtracker

import kotlinx.serialization.Serializable

@Serializable
data class BugTrackerUserSelectionDto(
    val clientId: String,
    val userId: String,
)
