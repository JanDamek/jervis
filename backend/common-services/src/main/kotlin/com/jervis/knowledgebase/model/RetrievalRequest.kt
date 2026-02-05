package com.jervis.knowledgebase.model

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import java.time.Instant

/**
 * Request to retrieve knowledge for a query.
 */
data class RetrievalRequest(
    val query: String,
    val clientId: ClientId,
    val projectId: ProjectId? = null,
    val asOf: Instant? = null, // for historical queries
    val minConfidence: Double = 0.0,
    val maxResults: Int = 10,
)
