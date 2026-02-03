package com.jervis.dto.integration

import kotlinx.serialization.Serializable

@Serializable
data class ProjectIntegrationOverridesDto(
    val projectId: String,
    val bugtrackerProjectKey: String? = null,
    val wikiSpaceKey: String? = null,
    val wikiRootPageId: String? = null,
    val projectEmailAccountId: String? = null,
    val gitCommitUserName: String? = null,
    val gitCommitUserEmail: String? = null,
    val commitMessageTemplate: String? = null,
)
