package com.jervis.service.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.jsoup.Jsoup
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

@Service
class LinkContentService {
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

                // Parse HTML via Jsoup to extract plain text
                val htmlContent = String(responseBody, Charsets.UTF_8)
                val doc = Jsoup.parse(htmlContent)
                doc.select("script, style, noscript").remove()
                val plainText = doc.text().trim()

                if (plainText.isEmpty() && responseBody.isNotEmpty()) {
                    logger.warn { "HTML parsing returned empty content for URL $url. Using raw UTF-8 as fallback." }
                    return@withContext LinkPlainText(
                        url,
                        htmlContent,
                        contentType,
                        success = true,
                        errorMessage = "HTML parsing returned empty, fell back to raw UTF-8",
                    )
                }

                LinkPlainText(url, plainText, contentType, success = true, errorMessage = null)
            }.getOrElse { e ->
                logger.warn { "Failed to fetch or parse URL $url error:${e.message}" }
                LinkPlainText(url, "", null, success = false, errorMessage = e.message)
            }
        }
}
