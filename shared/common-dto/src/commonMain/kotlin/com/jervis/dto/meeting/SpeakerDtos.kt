package com.jervis.dto.meeting

import kotlinx.serialization.Serializable

@Serializable
data class SpeakerDto(
    val id: String,
    val clientId: String,
    val name: String,
    val nationality: String? = null,
    val languagesSpoken: List<String> = emptyList(),
    val notes: String? = null,
    val voiceSampleRef: VoiceSampleRefDto? = null,
    /** True if this speaker has at least one stored voice embedding */
    val hasVoiceprint: Boolean = false,
    /** Number of stored voice embeddings */
    val voiceprintCount: Int = 0,
    /** Labels of stored embeddings (for UI display) */
    val voiceprintLabels: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class VoiceSampleRefDto(
    val meetingId: String,
    val startSec: Double,
    val endSec: Double,
)

@Serializable
data class SpeakerCreateDto(
    val clientId: String,
    val name: String,
    val nationality: String? = null,
    val languagesSpoken: List<String> = emptyList(),
    val notes: String? = null,
)

@Serializable
data class SpeakerUpdateDto(
    val id: String,
    val name: String,
    val nationality: String? = null,
    val languagesSpoken: List<String> = emptyList(),
    val notes: String? = null,
)

@Serializable
data class SpeakerEmbeddingDto(
    val speakerId: String,
    val embedding: List<Float>,
    /** Optional label for this embedding (e.g., meeting title, conditions) */
    val label: String? = null,
    /** Meeting ID where this embedding was captured */
    val meetingId: String? = null,
)

@Serializable
data class SpeakerMappingDto(
    val meetingId: String,
    val speakerMapping: Map<String, String>,
)
