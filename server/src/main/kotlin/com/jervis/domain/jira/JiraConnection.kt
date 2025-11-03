package com.jervis.domain.jira

import java.time.Instant

/** Connection details and selections for a client Jira Cloud tenant. */
data class JiraConnection(
    val clientId: String,
    val tenant: JiraTenant,
    val email: String? = null,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
    val preferredUser: JiraAccountId? = null,
    val mainBoard: JiraBoardId? = null,
    val primaryProject: JiraProjectKey? = null,
    val updatedAt: Instant = Instant.now(),
)
