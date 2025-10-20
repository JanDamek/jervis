package com.jervis.domain.background

import kotlinx.serialization.Serializable
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

enum class ArtifactType {
    RAG_NOTE,
    RAG_LINK,
    EVIDENCE,
    PLAN,
    DRAFT_REPLY,
    GUIDELINE_PROPOSAL,
}

@Serializable
data class SourceRef(
    val type: SourceRefType,
    val id: String,
    val offset: Int? = null,
    val lineStart: Int? = null,
    val lineEnd: Int? = null,
)

enum class SourceRefType {
    DOC,
    CODE,
    THREAD,
    PROJECT,
    MEETING,
}
