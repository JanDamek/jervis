package com.jervis.rag.internal.model

import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import com.jervis.types.SourceUrn

/**
 * Metadata for cross-linking RAG chunks with Graph nodes.
 * Security: clientId is MANDATORY - ensures client isolation.
 */
data class RagMetadata(
    val clientId: ClientId,
    val projectId: ProjectId?,
    val sourceUrn: SourceUrn,
    val graphRefs: List<String>,
)
