package com.jervis.dto.bugtracker

import kotlinx.serialization.Serializable

@Serializable
data class BugTrackerSetupStatusDto(
    val clientId: String,
    val connected: Boolean,
    val tenant: String? = null,
    val email: String? = null,
    val tokenPresent: Boolean = false,
    val apiToken: String? = null,
    val primaryProject: String? = null,
    val mainBoardId: String? = null,
    val preferredUser: String? = null,
)
