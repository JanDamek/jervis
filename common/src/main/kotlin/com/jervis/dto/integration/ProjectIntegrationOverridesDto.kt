package com.jervis.dto.integration

import kotlinx.serialization.Serializable

@Serializable
data class ProjectIntegrationOverridesDto(
    val projectId: String,
    val jiraProjectKey: String? = null,
    // Jira board override: String semantics -> null = unchanged, "" = clear, numeric string = set to that ID
    val jiraBoardId: String? = null,
    val confluenceSpaceKey: String? = null,
    val confluenceRootPageId: String? = null,
)
