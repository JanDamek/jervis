package com.jervis.knowledgebase.model

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import java.time.Instant

/**
 * Request to ingest new knowledge.
 */
data class IngestRequest(
    val clientId: ClientId,
    val projectId: ProjectId? = null,
    val sourceUrn: SourceUrn,
    val kind: String = "",
    val content: String,
    val metadata: Map<String, Any> = emptyMap(),
    val observedAt: Instant = Instant.now(),
)
