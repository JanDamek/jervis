package com.jervis.service.gateway

import org.springframework.core.io.Resource
import java.nio.file.Path

/**
 * Speech-to-text gateway abstraction.
 * Implementations should transcribe an audio file and return structured metadata.
 */
interface WhisperGateway {
    data class WhisperTranscriptionResponse(
        val text: String,
        val language: String? = null,
        val duration: Float? = null,
        val segments: List<Segment> = emptyList(),
    ) {
        data class Segment(
            val start: Float? = null,
            val end: Float? = null,
            val text: String,
        )
    }

    suspend fun transcribeAudioFile(
        audioFile: Path,
        model: String,
        language: String? = null,
    ): WhisperTranscriptionResponse
}
