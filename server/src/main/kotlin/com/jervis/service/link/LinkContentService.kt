package com.jervis.service.link

import com.jervis.service.document.TikaDocumentProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

@Service
class LinkContentService(
    private val tikaDocumentProcessor: TikaDocumentProcessor,
) {
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    data class LinkPlainText(
        val url: String,
        val plainText: String,
        val contentType: String?,
        val success: Boolean,
        val errorMessage: String? = null,
    )

    suspend fun fetchPlainText(url: String): LinkPlainText =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = URI.create(url)
                val request =
                    HttpRequest
                        .newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(60))
                        .header("User-Agent", "Mozilla/5.0 (compatible; JervisBot/1.0)")
                        .GET()
                        .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
                if (response.statusCode() != 200) {
                    return@withContext LinkPlainText(
                        url,
                        "",
                        null,
                        success = false,
                        errorMessage = "HTTP ${response.statusCode()}",
                    )
                }

                val contentType = response.headers().firstValue("content-type").orElse(null)
                val responseBody = response.body()

                // Skip empty responses
                if (responseBody.isEmpty()) {
                    return@withContext LinkPlainText(
                        url,
                        "",
                        contentType,
                        success = false,
                        errorMessage = "Empty response body",
                    )
                }

                val fileName = extractFileNameFromUri(uri, contentType)
                val processingResult =
                    tikaDocumentProcessor.processDocumentStream(
                        inputStream = ByteArrayInputStream(responseBody),
                        fileName = fileName,
                        sourceLocation =
                            TikaDocumentProcessor.SourceLocation(
                                documentPath = url,
                            ),
                    )

                if (!processingResult.success) {
                    return@withContext LinkPlainText(
                        url,
                        "",
                        contentType,
                        success = false,
                        errorMessage = processingResult.errorMessage,
                    )
                }

                LinkPlainText(url, processingResult.plainText, contentType, success = true, errorMessage = null)
            }.getOrElse { e ->
                logger.warn { "Failed to fetch or parse URL $url error:${e.message}" }
                LinkPlainText(url, "", null, success = false, errorMessage = e.message)
            }
        }

    private fun extractFileNameFromUri(
        uri: URI,
        contentType: String?,
    ): String {
        val path = uri.path ?: ""
        val lastSegment = path.substringAfterLast('/', "")
        if (lastSegment.isNotBlank()) return lastSegment

        return when {
            contentType?.contains("html", ignoreCase = true) == true -> "index.html"
            contentType?.contains("pdf", ignoreCase = true) == true -> "document.pdf"
            else -> "content.bin"
        }
    }
}
