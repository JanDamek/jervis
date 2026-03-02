package com.jervis.service.meeting

import com.jervis.configuration.properties.WhisperProperties
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import mu.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * HTTP client for coordinating whisper GPU access with the Ollama Router.
 *
 * Before starting a whisper transcription, Kotlin calls [acquireWhisperGpu] to
 * request p40-2 GPU permission from the router. The router blocks until the GPU
 * is available (no VLM running). After transcription, [releaseWhisperGpu] frees
 * the lock so VLM requests can proceed.
 *
 * If the router is unreachable, acquire returns true (fallback — whisper works
 * as before without coordination).
 */
@Component
class OllamaRouterClient(
    private val whisperProperties: WhisperProperties,
) {
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 3_600_000   // 1h (waiting for VLM to finish)
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 3_600_000
        }
    }

    /**
     * Acquire p40-2 GPU for whisper transcription.
     * Blocks until the router grants permission (VLM not running).
     * Returns true if granted or if router is unreachable (fallback).
     */
    suspend fun acquireWhisperGpu(): Boolean {
        val url = "${whisperProperties.ollamaRouterUrl}/router/whisper-acquire"
        return try {
            logger.info { "Acquiring whisper GPU from router: $url" }
            val response: HttpResponse = client.post(url)
            when (response.status) {
                HttpStatusCode.OK -> {
                    logger.info { "Whisper GPU acquired from router" }
                    true
                }
                else -> {
                    logger.warn { "Whisper GPU acquire returned ${response.status}, proceeding anyway" }
                    true
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to acquire whisper GPU from router, proceeding without coordination" }
            true // Fallback: proceed without lock (router may be down)
        }
    }

    /**
     * Release p40-2 GPU after whisper transcription completes.
     * Best-effort — errors are logged but not propagated.
     */
    suspend fun releaseWhisperGpu() {
        val url = "${whisperProperties.ollamaRouterUrl}/router/whisper-release"
        try {
            val response: HttpResponse = client.post(url)
            logger.info { "Whisper GPU released: ${response.status}" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to release whisper GPU (router may have restarted)" }
        }
    }
}
