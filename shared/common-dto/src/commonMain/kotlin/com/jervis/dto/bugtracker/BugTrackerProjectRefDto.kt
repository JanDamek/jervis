package com.jervis.dto.bugtracker

import kotlinx.serialization.Serializable

@Serializable
data class BugTrackerProjectRefDto(
    val key: String,
    val name: String,
)
