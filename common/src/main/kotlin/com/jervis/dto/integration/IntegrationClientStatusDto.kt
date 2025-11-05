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
