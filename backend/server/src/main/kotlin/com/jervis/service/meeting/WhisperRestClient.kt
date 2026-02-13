package com.jervis.service.meeting

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
 * Used when deploymentMode=rest_remote — sends audio file over HTTP multipart
 * to a persistent Whisper server and reads an SSE stream for progress + result.
 *
 * SSE event types from server:
 * - "progress": {"percent": 45.2, "segments_done": 128, "elapsed_seconds": 340}
 * - "result": full WhisperResult JSON
 * - "error": {"text": "", "segments": [], "error": "..."}
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
     * Reads SSE stream with progress updates and final result.
     *
     * @param baseUrl Base URL of the Whisper REST service (e.g. "http://192.168.100.117:8786")
     * @param audioFilePath Local path to audio file
     * @param optionsJson JSON string with Whisper options
     * @param onProgress Called with progress updates (percent, segmentsDone, elapsedSeconds)
     * @return WhisperResult with transcription
     */
    suspend fun transcribe(
        baseUrl: String,
        audioFilePath: String,
        optionsJson: String,
        onProgress: (suspend (percent: Double, segmentsDone: Int, elapsedSeconds: Double, lastSegmentText: String?) -> Unit)? = null,
    ): WhisperResult {
        val audioPath = Path.of(audioFilePath)
        val audioBytes = Files.readAllBytes(audioPath)
        val fileName = audioPath.fileName.toString()

        logger.info { "Sending audio to Whisper REST service: $baseUrl/transcribe (file=$fileName, ${audioBytes.size} bytes)" }

        val response: HttpResponse = client.submitFormWithBinaryData(
            url = "$baseUrl/transcribe",
            formData = formData {
                append("audio", audioBytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    append(HttpHeaders.ContentType, "audio/wav")
                })
                append("options", optionsJson)
            },
        )

        if (response.status != HttpStatusCode.OK) {
            val errorText = try {
                val channel = response.bodyAsChannel()
                buildString {
                    while (true) {
                        val line = channel.readUTF8Line() ?: break
                        append(line)
                    }
                }
            } catch (_: Exception) { "unknown error" }
            logger.error { "Whisper REST service returned ${response.status}: ${errorText.take(500)}" }
            return WhisperResult(
                text = "",
                segments = emptyList(),
                error = "Whisper REST error (${response.status}): ${errorText.take(500)}",
            )
        }

        // Read SSE stream
        return readSseStream(response, onProgress)
    }

    /**
     * Parse SSE event stream from Whisper REST server.
     *
     * SSE format:
     *   event: progress
     *   data: {"percent": 45.2, ...}
     *
     *   event: result
     *   data: {"text": "...", "segments": [...], ...}
     *
     *   event: error
     *   data: {"text": "", "segments": [], "error": "..."}
     */
    private suspend fun readSseStream(
        response: HttpResponse,
        onProgress: (suspend (percent: Double, segmentsDone: Int, elapsedSeconds: Double, lastSegmentText: String?) -> Unit)?,
    ): WhisperResult {
        val channel = response.bodyAsChannel()
        var currentEvent = ""
        var dataBuffer = StringBuilder()
        var result: WhisperResult? = null

        while (true) {
            val line = channel.readUTF8Line() ?: break

            when {
                line.startsWith("event:") -> {
                    currentEvent = line.removePrefix("event:").trim()
                }
                line.startsWith("data:") -> {
                    dataBuffer.append(line.removePrefix("data:").trim())
                }
                line.isEmpty() -> {
                    // Empty line = end of SSE event, process it
                    if (currentEvent.isNotEmpty() && dataBuffer.isNotEmpty()) {
                        val data = dataBuffer.toString()
                        when (currentEvent) {
                            "progress" -> {
                                try {
                                    val progress = json.decodeFromString<WhisperSseProgress>(data)
                                    logger.debug { "Whisper REST progress: ${progress.percent}% (${progress.segmentsDone} segments)" }
                                    onProgress?.invoke(progress.percent, progress.segmentsDone, progress.elapsedSeconds, progress.lastSegmentText)
                                } catch (e: Exception) {
                                    logger.warn { "Failed to parse progress SSE event: ${data.take(200)}" }
                                }
                            }
                            "result" -> {
                                try {
                                    result = json.decodeFromString<WhisperResult>(data)
                                    logger.info { "Whisper REST result received: ${result!!.segments.size} segments, ${result!!.text.length} chars" }
                                } catch (e: Exception) {
                                    logger.error(e) { "Failed to parse result SSE event: ${data.take(500)}" }
                                    result = WhisperResult(
                                        text = "",
                                        segments = emptyList(),
                                        error = "Failed to parse Whisper REST result: ${e.message}",
                                    )
                                }
                            }
                            "error" -> {
                                try {
                                    result = json.decodeFromString<WhisperResult>(data)
                                    logger.error { "Whisper REST error: ${result!!.error}" }
                                } catch (e: Exception) {
                                    logger.error(e) { "Failed to parse error SSE event: ${data.take(500)}" }
                                    result = WhisperResult(
                                        text = "",
                                        segments = emptyList(),
                                        error = "Whisper REST error (unparseable): ${data.take(500)}",
                                    )
                                }
                            }
                        }
                    }
                    currentEvent = ""
                    dataBuffer = StringBuilder()
                }
            }
        }

        return result ?: WhisperResult(
            text = "",
            segments = emptyList(),
            error = "Whisper REST stream ended without result or error event",
        )
    }
}

/** SSE progress event from the Whisper REST server (includes last_segment_text). */
@Serializable
private data class WhisperSseProgress(
    val percent: Double = 0.0,
    @SerialName("segments_done")
    val segmentsDone: Int = 0,
    @SerialName("elapsed_seconds")
    val elapsedSeconds: Double = 0.0,
    @SerialName("last_segment_text")
    val lastSegmentText: String? = null,
)
