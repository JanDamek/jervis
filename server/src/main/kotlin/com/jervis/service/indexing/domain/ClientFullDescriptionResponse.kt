package com.jervis.service.indexing.domain

import kotlinx.serialization.Serializable

/**
 * Response DTO for client full description generation
 */
@Serializable
data class ClientFullDescriptionResponse(
    val fullDescription: String = "",
)
