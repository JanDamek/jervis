package com.jervis.dto.integration

import kotlinx.serialization.Serializable

@Serializable
data class IntegrationProjectStatusDto(
    val projectId: String,
    val clientId: String,
    // Effective settings resolved from client defaults + overrides
    val effectiveBugtrackerProjectKey: String? = null,
    val overrideBugtrackerProjectKey: String? = null,
    val effectiveWikiSpaceKey: String? = null,
    val overrideWikiSpaceKey: String? = null,
    val effectiveWikiRootPageId: String? = null,
    val overrideWikiRootPageId: String? = null,
)
