package com.jervis.service.indexing.dto

import kotlinx.serialization.Serializable

/**
 * Response DTO for client short description generation
 */
@Serializable
data class ClientShortDescriptionResponse(
    val shortDescription: String = "",
)

/**
 * Response DTO for client full description generation
 */
@Serializable
data class ClientFullDescriptionResponse(
    val fullDescription: String = "",
)
