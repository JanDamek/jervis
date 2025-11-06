package com.jervis.dto.jira

import kotlinx.serialization.Serializable

@Serializable
data class JiraSetupStatusDto(
    val clientId: String,
    val connected: Boolean,
    val tenant: String? = null,
    val email: String? = null,
    val tokenPresent: Boolean = false,
    val primaryProject: String? = null,
    val mainBoard: Long? = null,
    val preferredUser: String? = null,
)
