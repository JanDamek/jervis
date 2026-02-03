package com.jervis.dto.bugtracker

import kotlinx.serialization.Serializable

@Serializable
data class BugTrackerApiTokenTestResponseDto(
    val success: Boolean,
    val message: String? = null,
)
