package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class ClientProjectLinkDto(
    val id: String,
    val clientId: String,
    val projectId: String,
    val isDisabled: Boolean = false,
    val anonymizationEnabled: Boolean = true,
    val historical: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
)
