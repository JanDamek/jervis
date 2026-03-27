package com.jervis.meeting

import com.jervis.common.types.ClientId
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "speakers")
@CompoundIndex(name = "clients_name_idx", def = "{'clientIds': 1, 'name': 1}")
data class SpeakerDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    /** Clients this speaker is associated with (one person can be across multiple clients) */
    val clientIds: List<ClientId> = emptyList(),
    val name: String,
    val nationality: String? = null,
    val languagesSpoken: List<String> = emptyList(),
    val notes: String? = null,
    /** Email addresses associated with this speaker */
    val emails: List<String> = emptyList(),
    /** Communication channels (Teams, Slack, Discord, etc.) */
    val channels: List<SpeakerChannelEntry> = emptyList(),
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

data class SpeakerChannelEntry(
    /** Reference to a Connection (Teams, Slack, Discord, etc.) */
    val connectionId: String,
    /** User identifier in the source system (user ID, handle, etc.) */
    val identifier: String,
    /** Human-readable display name */
    val displayName: String? = null,
)

data class VoiceSampleRef(
    val meetingId: ObjectId,
    val startSec: Double,
    val endSec: Double,
)
