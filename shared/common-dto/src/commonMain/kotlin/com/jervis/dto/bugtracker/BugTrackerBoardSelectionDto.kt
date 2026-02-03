package com.jervis.dto.bugtracker

import kotlinx.serialization.Serializable

@Serializable
data class BugTrackerBoardSelectionDto(
    val clientId: String,
    val boardId: String,
)
