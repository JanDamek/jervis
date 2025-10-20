package com.jervis.dto

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
