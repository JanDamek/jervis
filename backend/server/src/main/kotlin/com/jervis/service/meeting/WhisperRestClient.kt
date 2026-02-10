package com.jervis.service.meeting

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

private val json = Json { ignoreUnknownKeys = true }

/**
 * REST client for calling a remote Whisper transcription service.
 *
 * Used when [WhisperDeploymentMode.REST_REMOTE] is configured — sends audio file
 * over HTTP multipart to a persistent Whisper server instead of spawning K8s Jobs.
 */
@Component
class WhisperRestClient {

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            // Whisper transcription can take a very long time (large files, large models)
            requestTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = Long.MAX_VALUE
        }
    }

    /**
     * Check if the remote Whisper REST service is reachable.
     */
    suspend fun isHealthy(baseUrl: String): Boolean {
        return try {
            val response = client.get("$baseUrl/health")
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.warn(e) { "Whisper REST health check failed for $baseUrl" }
            false
        }
    }

    /**
     * Send audio file to remote Whisper REST service for transcription.
     *
     * @param baseUrl Base URL of the Whisper REST service (e.g. "http://192.168.100.117:8786")
     * @param audioFilePath Local path to audio file
     * @param optionsJson JSON string with Whisper options
     * @return WhisperResult with transcription
     */
    suspend fun transcribe(
        baseUrl: String,
        audioFilePath: String,
        optionsJson: String,
    ): WhisperResult {
        val audioPath = Path.of(audioFilePath)
        val audioBytes = Files.readAllBytes(audioPath)
        val fileName = audioPath.fileName.toString()

        logger.info { "Sending audio to Whisper REST service: $baseUrl/transcribe (file=$fileName, ${audioBytes.size} bytes)" }

        val response = client.submitFormWithBinaryData(
            url = "$baseUrl/transcribe",
            formData = formData {
                append("audio", audioBytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    append(HttpHeaders.ContentType, "audio/wav")
                })
                append("options", optionsJson)
            },
        )

        val responseText = response.bodyAsText()

        if (response.status != HttpStatusCode.OK) {
            logger.error { "Whisper REST service returned ${response.status}: $responseText" }
            return WhisperResult(
                text = "",
                segments = emptyList(),
                error = "Whisper REST error (${response.status}): ${responseText.take(500)}",
            )
        }

        return try {
            json.decodeFromString<WhisperResult>(responseText)
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse Whisper REST response: ${responseText.take(500)}" }
            WhisperResult(
                text = "",
                segments = emptyList(),
                error = "Failed to parse Whisper REST response: ${e.message}",
            )
        }
    }
}
