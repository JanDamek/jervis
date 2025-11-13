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
    // Project commit email override: String semantics -> null = unchanged, "" = clear, non-empty = set to account ID
    val projectEmailAccountId: String? = null,
    // Git commit identity overrides: null = unchanged, "" = clear, non-empty = set value
    val gitCommitUserName: String? = null,
    val gitCommitUserEmail: String? = null,
    // Commit message template override: String semantics -> null = unchanged, "" = clear, non-empty = set template
    val commitMessageTemplate: String? = null,
)
