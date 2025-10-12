package com.jervis.service.gateway

import java.nio.file.Path

/**
 * Gateway interface for Whisper speech-to-text API integration.
 */
interface WhisperGateway {
    data class WhisperTranscriptionRequest(
        val model: String = "base",
        val language: String? = null,
        val temperature: Float = 0.0f,
        val responseFormat: String = "json",
    )

    data class WhisperTranscriptionResponse(
        val text: String,
        val language: String? = null,
        val duration: Float? = null,
        val segments: List<TranscriptSegment> = emptyList(),
    )

    data class TranscriptSegment(
        val id: Int,
        val start: Float,
        val end: Float,
        val text: String,
    )

    suspend fun transcribeAudioFile(
        audioFile: Path,
        model: String = "base",
        language: String? = null,
    ): WhisperTranscriptionResponse

    suspend fun transcribeAudioBytes(
        audioBytes: ByteArray,
        fileName: String,
        model: String = "base",
        language: String? = null,
    ): WhisperTranscriptionResponse
}
