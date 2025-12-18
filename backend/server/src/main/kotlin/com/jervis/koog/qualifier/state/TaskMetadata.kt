package com.jervis.koog.qualifier.state

import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import com.jervis.types.SourceUrn

/**
 * Immutable task metadata (context) for the entire qualification pipeline.
 * This data never changes during processing.
 */
data class TaskMetadata(
    val correlationId: String,
    val clientId: ClientId,
    val projectId: ProjectId?,
    val sourceUrn: SourceUrn,
)
