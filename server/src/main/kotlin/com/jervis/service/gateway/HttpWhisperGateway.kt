package com.jervis.service.gateway

import com.jervis.configuration.AudioWhisperProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.nio.file.Path
import kotlin.io.path.readBytes

/**
 * HTTP-based implementation of WhisperGateway using WebClient.
 */
@Service
@ConditionalOnProperty(name = ["audio.whisper.enabled"], havingValue = "true", matchIfMissing = false)
class HttpWhisperGateway(
    private val webClientBuilder: WebClient.Builder,
    private val audioWhisperProps: AudioWhisperProperties,
) : WhisperGateway {
    private val logger = KotlinLogging.logger {}

    private val webClient: WebClient by lazy {
        webClientBuilder
            .baseUrl(audioWhisperProps.apiUrl)
            .build()
    }

    override suspend fun transcribeAudioFile(
        audioFile: Path,
        model: String,
        language: String?,
    ): WhisperGateway.WhisperTranscriptionResponse =
        withContext(Dispatchers.IO) {
            logger.info { "Transcribing audio file: ${audioFile.fileName} with model: $model" }

            val multipartBody =
                MultipartBodyBuilder()
                    .apply {
                        part("file", audioFile.readBytes())
                            .filename(audioFile.fileName.toString())
                        part("model", model)
                        language?.let { part("language", it) }
                        part("response_format", "verbose_json")
                    }.build()

            webClient
                .post()
                .uri("/v1/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(multipartBody)
                .retrieve()
                .awaitBody<WhisperGateway.WhisperTranscriptionResponse>()
        }

    override suspend fun transcribeAudioBytes(
        audioBytes: ByteArray,
        fileName: String,
        model: String,
        language: String?,
    ): WhisperGateway.WhisperTranscriptionResponse =
        withContext(Dispatchers.IO) {
            logger.info { "Transcribing audio bytes: $fileName (${audioBytes.size} bytes) with model: $model" }

            val multipartBody =
                MultipartBodyBuilder()
                    .apply {
                        part("file", audioBytes).filename(fileName)
                        part("model", model)
                        language?.let { part("language", it) }
                        part("response_format", "verbose_json")
                    }.build()

            webClient
                .post()
                .uri("/v1/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(multipartBody)
                .retrieve()
                .awaitBody<WhisperGateway.WhisperTranscriptionResponse>()
        }
}
