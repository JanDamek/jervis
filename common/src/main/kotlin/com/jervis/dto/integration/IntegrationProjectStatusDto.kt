package com.jervis.dto.integration

import kotlinx.serialization.Serializable

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
