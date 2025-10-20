package com.jervis.domain.background

import org.bson.types.ObjectId
import java.time.Instant

data class BackgroundArtifact(
    val id: ObjectId = ObjectId(),
    val taskId: ObjectId,
    val type: ArtifactType,
    val payload: Map<String, Any>,
    val sourceRef: SourceRef,
    val contentHash: String,
    val confidence: Double,
    val createdAt: Instant = Instant.now(),
)
