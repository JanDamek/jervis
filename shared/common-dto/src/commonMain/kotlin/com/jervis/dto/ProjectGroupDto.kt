package com.jervis.dto

import com.jervis.common.Constants
import com.jervis.dto.connection.ConnectionCapability
import kotlinx.serialization.Serializable

/**
 * Cross-platform DTO for project groups.
 *
 * Groups organize projects within a client for shared KB cross-visibility
 * and shared connections/resources.
 */
@Serializable
data class ProjectGroupDto(
    val id: String = Constants.GLOBAL_ID_STRING,
    val clientId: String,
    val name: String,
    val description: String? = null,
    /** Connection capabilities at group level */
    val connectionCapabilities: List<ProjectConnectionCapabilityDto> = emptyList(),
    /** Group-level shared resources */
    val resources: List<ProjectResourceDto> = emptyList(),
    /** N:M links between group-level resources */
    val resourceLinks: List<ResourceLinkDto> = emptyList(),
)
