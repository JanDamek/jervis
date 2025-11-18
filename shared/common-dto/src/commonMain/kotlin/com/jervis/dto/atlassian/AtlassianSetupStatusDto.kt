package com.jervis.dto.atlassian

import kotlinx.serialization.Serializable

@Serializable
data class AtlassianSetupStatusDto(
    val clientId: String,
    val connected: Boolean,
    val tenant: String? = null,
    val email: String? = null,
    val tokenPresent: Boolean = false,
    val apiToken: String? = null,
    val primaryProject: String? = null,
    val mainBoard: Long? = null,
    val preferredUser: String? = null,
)
