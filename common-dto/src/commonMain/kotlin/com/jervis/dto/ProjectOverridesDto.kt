package com.jervis.dto

import com.jervis.domain.git.GitAuthTypeEnum
import kotlinx.serialization.Serializable

@Serializable
data class ProjectOverridesDto(
    val gitRemoteUrl: String? = null,
    val gitAuthType: GitAuthTypeEnum? = null,
    val gitConfig: GitConfigDto? = null,
    // Jira override for project
    val jiraProjectKey: String? = null,
    // Jira: per-project override of main board ID
    val jiraBoardId: Long? = null,
    // Confluence documentation overrides for project
    val confluenceSpaceKey: String? = null,
    val confluenceRootPageId: String? = null,
)
