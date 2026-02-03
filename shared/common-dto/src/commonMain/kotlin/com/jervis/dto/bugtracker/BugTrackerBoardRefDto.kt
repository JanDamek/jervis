package com.jervis.dto.bugtracker

import kotlinx.serialization.Serializable

@Serializable
data class BugTrackerBoardRefDto(
    val id: String,
    val name: String,
)
