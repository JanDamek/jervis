package com.jervis.di

import com.jervis.api.SecurityConstants
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line

actual suspend fun postSseStream(
    url: String,
    bodyBytes: ByteArray,
    contentType: String,
    onEvent: suspend (SseEvent) -> Unit,
) {
    val client = createPlatformHttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000   // Total request — TTS can take 60s+ for long text
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000     // Between chunks — XTTS synthesis takes 2-10s per sentence
        }
    }
    try {
        val response = client.post(url) {
            header(SecurityConstants.CLIENT_HEADER, SecurityConstants.CLIENT_TOKEN)
            header(SecurityConstants.PLATFORM_HEADER, SecurityConstants.PLATFORM_DESKTOP)
            contentType(ContentType.parse(contentType))
            setBody(bodyBytes)
        }

        val channel = response.bodyAsChannel()
        var currentEvent = ""
        var currentData = ""

        while (!channel.isClosedForRead) {
            // Use large limit — TTS audio base64 can be 100K+ chars per line
            val line = channel.readUTF8Line(1_000_000) ?: break
            when {
                line.startsWith("event: ") -> currentEvent = line.removePrefix("event: ").trim()
                line.startsWith("data: ") -> currentData = line.removePrefix("data: ").trim()
                line.isBlank() && currentData.isNotEmpty() -> {
                    onEvent(SseEvent(currentEvent, currentData))
                    currentEvent = ""
                    currentData = ""
                }
            }
        }
    } finally {
        client.close()
    }
}
