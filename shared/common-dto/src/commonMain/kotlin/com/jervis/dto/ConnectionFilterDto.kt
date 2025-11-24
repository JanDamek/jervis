package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * Connection filter DTO for client/project-level filtering.
 */
@Serializable
data class ConnectionFilterDto(
    val connectionId: String,
    val jiraProjects: List<String> = emptyList(),
    val jiraBoardIds: List<String> = emptyList(),
    val confluenceSpaces: List<String> = emptyList(),
    val emailFolders: List<String> = emptyList(),
)
