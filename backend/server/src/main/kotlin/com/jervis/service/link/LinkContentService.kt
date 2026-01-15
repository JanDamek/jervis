package com.jervis.service.link

import com.jervis.common.client.ITikaClient
import com.jervis.common.dto.TikaProcessRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

private val logger = KotlinLogging.logger {}

@Service
class LinkContentService(
    private val tikaClient: ITikaClient,
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
                    tikaClient.process(
                        TikaProcessRequest(
                            source =
                                TikaProcessRequest.Source.FileBytes(
                                    fileName = fileName,
                                    dataBase64 = Base64.getEncoder().encodeToString(responseBody),
                                ),
                            includeMetadata = true,
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

                val plainText = processingResult.plainText.trim()
                if (plainText.isEmpty() && responseBody.isNotEmpty()) {
                    logger.warn { "Tika returned empty content for URL $url. Using original response body as text to prevent data loss." }
                    return@withContext LinkPlainText(
                        url,
                        String(responseBody, Charsets.UTF_8),
                        contentType,
                        success = true,
                        errorMessage = "Tika returned empty content, fell back to raw UTF-8",
                    )
                }

                LinkPlainText(url, plainText, contentType, success = true, errorMessage = null)
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
