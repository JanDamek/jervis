package com.jervis.service.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.nio.file.Path
import kotlin.io.path.readBytes

/**
 * Gateway for Whisper speech-to-text API integration.
 */
@Service
class WhisperGateway(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${audio.whisper.api-url}") private val whisperApiUrl: String,
    @Value("\${audio.whisper.timeout-seconds}") private val timeoutSeconds: Long,
) {
    private val logger = KotlinLogging.logger {}

    private val webClient: WebClient by lazy {
        webClientBuilder
            .baseUrl(whisperApiUrl)
            .build()
    }

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

    /**
     * Transcribe audio file to text using Whisper API
     */
    suspend fun transcribeAudioFile(
        audioFile: Path,
        model: String = "base",
        language: String? = null,
    ): WhisperTranscriptionResponse = withContext(Dispatchers.IO) {
        logger.info { "Transcribing audio file: ${'$'}{audioFile.fileName} with model: ${'$'}model" }

        val multipartBody = MultipartBodyBuilder().apply {
            part("file", audioFile.readBytes())
                .filename(audioFile.fileName.toString())
            part("model", model)
            language?.let { part("language", it) }
            part("response_format", "verbose_json")
        }.build()

        webClient.post()
            .uri("/v1/audio/transcriptions")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(multipartBody)
            .retrieve()
            .awaitBody<WhisperTranscriptionResponse>()
    }

    /**
     * Transcribe audio from byte array
     */
    suspend fun transcribeAudioBytes(
        audioBytes: ByteArray,
        fileName: String,
        model: String = "base",
        language: String? = null,
    ): WhisperTranscriptionResponse = withContext(Dispatchers.IO) {
        logger.info { "Transcribing audio bytes: ${'$'}fileName (${'$'}{audioBytes.size} bytes) with model: ${'$'}model" }

        val multipartBody = MultipartBodyBuilder().apply {
            part("file", audioBytes).filename(fileName)
            part("model", model)
            language?.let { part("language", it) }
            part("response_format", "verbose_json")
        }.build()

        webClient.post()
            .uri("/v1/audio/transcriptions")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(multipartBody)
            .retrieve()
            .awaitBody<WhisperTranscriptionResponse>()
    }
}
