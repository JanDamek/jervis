package com.jervis.ui.audio

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Client for the Jervis TTS service.
 * Sends text to the TTS backend and receives WAV audio.
 *
 * Usage:
 * ```
 * val client = TtsClient(httpClient, "https://jervis-tts.damek-soft.eu")
 * val wavBytes = client.synthesize("Hello world")
 * audioPlayer.play(wavBytes)
 * ```
 */
class TtsClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) {
    @Serializable
    private data class TtsRequest(
        val text: String,
        val voice: String? = null,
        val speed: Float = 1.0f,
    )

    /**
     * Synthesize text to speech, returning WAV audio bytes.
     * @param text Text to speak
     * @param speed Speaking rate (1.0 = normal, 0.5 = slow, 2.0 = fast)
     * @return WAV audio data
     */
    suspend fun synthesize(text: String, speed: Float = 1.0f): ByteArray {
        val response = httpClient.post("$baseUrl/tts") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(TtsRequest.serializer(), TtsRequest(text = text, speed = speed)))
        }
        return response.bodyAsBytes()
    }
}
