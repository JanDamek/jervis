package com.jervis.domain.atlassian

import com.jervis.domain.jira.JiraAccountId
import com.jervis.domain.jira.JiraBoardId
import com.jervis.domain.jira.JiraProjectKey
import com.jervis.domain.jira.JiraTenant
import java.time.Instant

/**
 * Connection details for a client's Atlassian Cloud (Jira + Confluence).
 * Uses simple API token authentication (not OAuth).
 */
data class AtlassianConnection(
    val clientId: String,
    val tenant: JiraTenant,
    val email: String? = null,
    /** Atlassian API token (works for both Jira and Confluence) */
    val accessToken: String,
    val preferredUser: JiraAccountId? = null,
    val mainBoard: JiraBoardId? = null,
    val primaryProject: JiraProjectKey? = null,
    val updatedAt: Instant = Instant.now(),
)
