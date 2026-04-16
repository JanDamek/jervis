package com.jervis.di

import com.jervis.api.SecurityConstants
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
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
            // Live assist / meeting companion streams run for the full meeting
            // duration. Only per-chunk inactivity (socketTimeout) caps the stream;
            // requestTimeout must not cap the whole resource.
            requestTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 120_000    // 2 min between chunks
        }
    }
    try {
        // Use preparePost + execute for true streaming — don't buffer full response
        val statement = client.preparePost(url) {
            header(SecurityConstants.CLIENT_HEADER, SecurityConstants.CLIENT_TOKEN)
            header(SecurityConstants.PLATFORM_HEADER, SecurityConstants.PLATFORM_DESKTOP)
            contentType(ContentType.parse(contentType))
            setBody(bodyBytes)
        }
        statement.execute { response ->
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
        }
    } finally {
        client.close()
    }
}
