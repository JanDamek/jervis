package com.jervis.service.gateway

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Default fallback implementation that fails fast if no real WhisperGateway is configured.
 * This keeps the application context bootable for components that do not use audio transcription.
 */
@Service
@ConditionalOnMissingBean(WhisperGateway::class)
class DefaultWhisperGateway : WhisperGateway {
    override suspend fun transcribeAudioFile(
        audioFile: Path,
        model: String,
        language: String?
    ): WhisperGateway.WhisperTranscriptionResponse {
        throw UnsupportedOperationException("No WhisperGateway implementation is configured. Please provide one via Spring context.")
    }
}
