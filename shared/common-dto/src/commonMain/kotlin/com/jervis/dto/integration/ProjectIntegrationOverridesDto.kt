package com.jervis.dto.integration

import kotlinx.serialization.Serializable

@Serializable
data class ProjectIntegrationOverridesDto(
    val projectId: String,
    val jiraProjectKey: String? = null,
    val jiraBoardId: String? = null,
    val confluenceSpaceKey: String? = null,
    val confluenceRootPageId: String? = null,
    val projectEmailAccountId: String? = null,
    val gitCommitUserName: String? = null,
    val gitCommitUserEmail: String? = null,
    val commitMessageTemplate: String? = null,
)
