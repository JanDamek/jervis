package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * Connection filter DTO for client/project-level filtering.
 */
@Serializable
data class ConnectionFilterDto(
    val connectionId: String,
    val bugtrackerProjects: List<String> = emptyList(),
    val bugtrackerBoardIds: List<String> = emptyList(),
    val wikiSpaces: List<String> = emptyList(),
    val emailFolders: List<String> = emptyList(),
)
