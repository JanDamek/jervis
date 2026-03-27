package com.jervis.dto.preferences

import kotlinx.serialization.Serializable

/**
 * System-level configuration DTO.
 */
@Serializable
data class SystemConfigDto(
    val jervisInternalProjectId: String? = null,
)

/**
 * Request DTO for updating system configuration.
 * Null fields are not updated.
 */
@Serializable
data class UpdateSystemConfigRequest(
    val jervisInternalProjectId: String? = null,
)
