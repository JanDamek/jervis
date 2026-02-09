package com.jervis.dto.meeting

import kotlinx.serialization.Serializable

@Serializable
data class TranscriptCorrectionSubmitDto(
    val clientId: String,
    val projectId: String? = null,
    val original: String,
    val corrected: String,
    val category: String = "general",
    val context: String? = null,
)

@Serializable
data class TranscriptCorrectionDto(
    val correctionId: String = "",
    val sourceUrn: String = "",
    val original: String = "",
    val corrected: String = "",
    val category: String = "general",
    val context: String? = null,
)
