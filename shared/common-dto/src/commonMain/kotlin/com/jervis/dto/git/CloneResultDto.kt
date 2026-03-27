package com.jervis.dto.git

import kotlinx.serialization.Serializable

/**
 * Result DTO from Git clone operation.
 */
@Serializable
data class CloneResultDto(
    val success: Boolean,
    val repositoryPath: String? = null,
    val message: String,
)
