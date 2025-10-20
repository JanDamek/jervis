package com.jervis.service.indexing.domain

import kotlinx.serialization.Serializable

/**
 * Response DTO for client short description generation
 */
@Serializable
data class ClientShortDescriptionResponse(
    val shortDescription: String = "",
)
