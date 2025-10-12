package com.jervis.service.gateway

import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Default implementation of WhisperGateway.
 * This implementation throws UnsupportedOperationException when called,
 * indicating that a proper Whisper API integration should be configured.
 */
@Service
class DefaultWhisperGateway : WhisperGateway {
    override suspend fun transcribeAudioFile(
        audioFile: Path,
        model: String,
        language: String?,
    ): WhisperGateway.WhisperTranscriptionResponse =
        throw UnsupportedOperationException("No WhisperGateway implementation is configured. Please provide one via Spring context.")

    override suspend fun transcribeAudioBytes(
        audioBytes: ByteArray,
        fileName: String,
        model: String,
        language: String?,
    ): WhisperGateway.WhisperTranscriptionResponse =
        throw UnsupportedOperationException("No WhisperGateway implementation is configured. Please provide one via Spring context.")
}
