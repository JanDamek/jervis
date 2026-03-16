package com.jervis.di

import com.jervis.api.SecurityConstants
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
    val client = createPlatformHttpClient { }
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
            val line = channel.readUTF8Line() ?: break
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
