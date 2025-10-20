package com.jervis.entity.mongo

import com.jervis.domain.background.ArtifactType
import com.jervis.domain.background.BackgroundArtifact
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "background_artifacts")
@CompoundIndexes(
    CompoundIndex(name = "task_created", def = "{'taskId': 1, 'createdAt': -1}"),
    CompoundIndex(name = "type_confidence", def = "{'type': 1, 'confidence': -1}"),
    CompoundIndex(name = "content_hash_unique", def = "{'contentHash': 1}", unique = true),
)
data class BackgroundArtifactDocument(
    @Id
    val id: ObjectId = ObjectId(),
    @Indexed
    val taskId: ObjectId,
    val type: String,
    val payload: Map<String, Any>,
    val sourceRef: String,
    @Indexed
    val contentHash: String,
    val confidence: Double,
    @Indexed
    val createdAt: Instant = Instant.now(),
) {
    fun toDomain(): BackgroundArtifact =
        BackgroundArtifact(
            id = id,
            taskId = taskId,
            type = ArtifactType.valueOf(type),
            payload = payload,
            sourceRef = Json.decodeFromString(sourceRef),
            contentHash = contentHash,
            confidence = confidence,
            createdAt = createdAt,
        )

    companion object {
        fun fromDomain(artifact: BackgroundArtifact): BackgroundArtifactDocument =
            BackgroundArtifactDocument(
                id = artifact.id,
                taskId = artifact.taskId,
                type = artifact.type.name,
                payload = artifact.payload,
                sourceRef = Json.encodeToString(artifact.sourceRef),
                contentHash = artifact.contentHash,
                confidence = artifact.confidence,
                createdAt = artifact.createdAt,
            )
    }
}
