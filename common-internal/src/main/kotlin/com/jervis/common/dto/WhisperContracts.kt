package com.jervis.common.dto

import kotlinx.serialization.Serializable

@Serializable
data class WhisperRequestDto(
    val audio: Audio,
    val diarization: Boolean = false,
) {
    @Serializable
    sealed class Audio {
        @Serializable
        data class Url(
            val url: String,
        ) : Audio()

        @Serializable
        data class Base64(
            val mimeType: String,
            val data: String,
        ) : Audio()
    }
}

@Serializable
data class WhisperResultDto(
    val text: String,
    val segments: List<Segment> = emptyList(),
) {
    @Serializable
    data class Segment(
        val startSec: Double,
        val endSec: Double,
        val text: String,
    )
}
