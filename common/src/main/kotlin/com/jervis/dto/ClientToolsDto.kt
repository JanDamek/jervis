package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class ClientToolsDto(
    val git: GitConnDto? = null,
    val jira: JiraConnDto? = null,
    val slack: SlackConnDto? = null,
    val teams: TeamsConnDto? = null,
    val discord: DiscordConnDto? = null,
    val email: EmailConnDto? = null,
)
