package com.jervis.domain.project

import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.domain.git.GitConfig

/**
 * Project-level override settings for integrations.
 * - Git overrides keep priority for repository access
 * - Jira overrides allow selecting a different project key than the client default
 * - Confluence overrides allow selecting a space/root page for project docs
 */
data class ProjectOverrides(
    val gitRemoteUrl: String? = null,
    val gitAuthType: GitAuthTypeEnum? = null,
    val gitConfig: GitConfig? = null,
    // Jira: per-project override of project key (client's Atlassian Cloud connection is used)
    val jiraProjectKey: String? = null,
    // Jira: per-project override of main board ID
    val jiraBoardId: Long? = null,
    // Confluence: per-project documentation space
    val confluenceSpaceKey: String? = null,
    // Confluence: optional root page id for the project's documentation tree
    val confluenceRootPageId: String? = null,
)
