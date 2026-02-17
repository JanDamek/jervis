package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * System-level configuration DTO.
 * Contains brain connection settings (Jervis's own Jira + Confluence).
 */
@Serializable
data class SystemConfigDto(
    val jervisInternalProjectId: String? = null,
    val brainBugtrackerConnectionId: String? = null,
    val brainBugtrackerProjectKey: String? = null,
    val brainWikiConnectionId: String? = null,
    val brainWikiSpaceKey: String? = null,
    val brainWikiRootPageId: String? = null,
)

/**
 * Request DTO for updating system configuration.
 * Null fields are not updated.
 */
@Serializable
data class UpdateSystemConfigRequest(
    val jervisInternalProjectId: String? = null,
    val brainBugtrackerConnectionId: String? = null,
    val brainBugtrackerProjectKey: String? = null,
    val brainWikiConnectionId: String? = null,
    val brainWikiSpaceKey: String? = null,
    val brainWikiRootPageId: String? = null,
)
