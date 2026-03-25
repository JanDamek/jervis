package com.jervis.service.link

import com.jervis.configuration.DocumentExtractionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

@Service
class LinkContentService(
    private val documentExtractionClient: DocumentExtractionClient,
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

                if (responseBody.isEmpty()) {
                    return@withContext LinkPlainText(
                        url,
                        "",
                        contentType,
                        success = false,
                        errorMessage = "Empty response body",
                    )
                }

                val htmlContent = String(responseBody, Charsets.UTF_8)
                val mimeType = contentType?.substringBefore(";")?.trim() ?: "text/html"
                val plainText = documentExtractionClient.extractText(htmlContent, mimeType)

                LinkPlainText(url, plainText, contentType, success = true, errorMessage = null)
            }.getOrElse { e ->
                logger.warn { "Failed to fetch or parse URL $url error:${e.message}" }
                LinkPlainText(url, "", null, success = false, errorMessage = e.message)
            }
        }
}
