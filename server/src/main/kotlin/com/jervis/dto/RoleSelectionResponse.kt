package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class RoleSelectionResponse(
    val role: String?,
)
