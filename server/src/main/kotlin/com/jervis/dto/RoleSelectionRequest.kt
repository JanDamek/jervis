package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * DTOs for reading and updating the role stored in the WebSession.
 */
@Serializable
data class RoleSelectionRequest(
    val role: String,
)
