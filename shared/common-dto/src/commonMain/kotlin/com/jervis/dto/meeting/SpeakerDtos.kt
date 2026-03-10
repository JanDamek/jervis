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
    /** True if this speaker has a stored voice embedding for auto-identification */
    val hasVoiceprint: Boolean = false,
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
)

@Serializable
data class SpeakerMappingDto(
    val meetingId: String,
    val speakerMapping: Map<String, String>,
)
