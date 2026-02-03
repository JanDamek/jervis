package com.jervis.dto.integration

import kotlinx.serialization.Serializable

@Serializable
data class IntegrationClientStatusDto(
    val clientId: String,
    // Bugtracker
    val bugtrackerConnected: Boolean,
    val bugtrackerTenant: String? = null,
    val bugtrackerPrimaryProject: String? = null,
    // Wiki defaults at client level
    val wikiSpaceKey: String? = null,
    val wikiRootPageId: String? = null,
)
