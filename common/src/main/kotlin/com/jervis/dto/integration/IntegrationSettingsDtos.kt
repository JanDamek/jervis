package com.jervis.dto.integration

import kotlinx.serialization.Serializable

@Serializable
data class IntegrationClientStatusDto(
    val clientId: String,
    // Jira
    val jiraConnected: Boolean,
    val jiraTenant: String? = null,
    val jiraPrimaryProject: String? = null,
    // Confluence defaults at client level
    val confluenceSpaceKey: String? = null,
    val confluenceRootPageId: String? = null,
)

@Serializable
data class ClientConfluenceDefaultsDto(
    val clientId: String,
    val confluenceSpaceKey: String?,
    val confluenceRootPageId: String?,
)

@Serializable
data class ProjectIntegrationOverridesDto(
    val projectId: String,
    val jiraProjectKey: String? = null,
    // Jira board override: String semantics -> null = unchanged, "" = clear, numeric string = set to that ID
    val jiraBoardId: String? = null,
    val confluenceSpaceKey: String? = null,
    val confluenceRootPageId: String? = null,
)

@Serializable
data class IntegrationProjectStatusDto(
    val projectId: String,
    val clientId: String,
    // Effective settings resolved from client defaults + overrides
    val effectiveJiraProjectKey: String? = null,
    val overrideJiraProjectKey: String? = null,
    val effectiveJiraBoardId: Long? = null,
    val overrideJiraBoardId: Long? = null,
    val effectiveConfluenceSpaceKey: String? = null,
    val overrideConfluenceSpaceKey: String? = null,
    val effectiveConfluenceRootPageId: String? = null,
    val overrideConfluenceRootPageId: String? = null,
)
