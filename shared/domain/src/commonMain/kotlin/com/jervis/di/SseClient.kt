package com.jervis.di

/**
 * Platform-specific SSE streaming POST client.
 *
 * JVM/Android: Ktor CIO engine (bodyAsChannel + readUTF8Line — works natively).
 * iOS: Native NSURLSession with data delegate (Darwin engine buffers the whole
 *       response, so Ktor's channel-based reading does NOT work for SSE).
 */

data class SseEvent(val event: String, val data: String)

/**
 * POST [bodyBytes] to [url] with [contentType], parse SSE events, and call
 * [onEvent] for each complete event (event + data + blank line).
 *
 * The caller is responsible for constructing the body (JSON or multipart).
 * Security headers (CLIENT_HEADER, PLATFORM_HEADER) are added internally
 * by each platform implementation.
 */
expect suspend fun postSseStream(
    url: String,
    bodyBytes: ByteArray,
    contentType: String,
    onEvent: suspend (SseEvent) -> Unit,
)

/**
 * Build a multipart/form-data body with one file field and additional text fields.
 * Returns (contentType with boundary, body bytes).
 */
fun buildMultipartBody(
    fileFieldName: String,
    fileName: String,
    fileContentType: String,
    fileBytes: ByteArray,
    fields: Map<String, String> = emptyMap(),
): Pair<String, ByteArray> {
    val boundary = "----KMP${kotlin.random.Random.Default.nextLong().toULong()}"
    val parts = mutableListOf<ByteArray>()

    // File part
    parts += "--$boundary\r\nContent-Disposition: form-data; name=\"$fileFieldName\"; filename=\"$fileName\"\r\nContent-Type: $fileContentType\r\n\r\n".encodeToByteArray()
    parts += fileBytes
    parts += "\r\n".encodeToByteArray()

    // Text fields
    for ((key, value) in fields) {
        parts += "--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n".encodeToByteArray()
    }

    // End boundary
    parts += "--$boundary--\r\n".encodeToByteArray()

    val body = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(body, offset)
        offset += part.size
    }

    return "multipart/form-data; boundary=$boundary" to body
}
