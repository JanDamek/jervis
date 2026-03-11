package com.jervis.entity

import com.jervis.common.types.ClientId
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "speakers")
@CompoundIndex(name = "client_name_idx", def = "{'clientId': 1, 'name': 1}")
data class SpeakerDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    val clientId: ClientId,
    val name: String,
    val nationality: String? = null,
    val languagesSpoken: List<String> = emptyList(),
    val notes: String? = null,
    val voiceSampleRef: VoiceSampleRef? = null,
    /** Multiple 256-dim pyannote voice embeddings (different conditions: normal, phone, car, sick...) */
    val voiceEmbeddings: List<VoiceEmbeddingEntry> = emptyList(),
    /** @deprecated Single embedding — kept for backward compat with existing DB documents */
    val voiceEmbedding: List<Float>? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    /** All embeddings: migrated single + multi list */
    fun allEmbeddings(): List<VoiceEmbeddingEntry> {
        val result = voiceEmbeddings.toMutableList()
        // Migrate legacy single embedding if present and not already in list
        if (voiceEmbedding != null && result.none { it.embedding == voiceEmbedding }) {
            result.add(0, VoiceEmbeddingEntry(embedding = voiceEmbedding, label = "original"))
        }
        return result
    }
}

data class VoiceEmbeddingEntry(
    val embedding: List<Float>,
    val label: String? = null,
    val meetingId: ObjectId? = null,
    val createdAt: Instant = Instant.now(),
)

data class VoiceSampleRef(
    val meetingId: ObjectId,
    val startSec: Double,
    val endSec: Double,
)
