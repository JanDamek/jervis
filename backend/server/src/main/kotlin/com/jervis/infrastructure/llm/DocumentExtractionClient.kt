package com.jervis.infrastructure.llm

import com.jervis.infrastructure.llm.DocumentExtractionClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * REST client for the Python document-extraction microservice.
 *
 * Replaces local Jsoup-based TikaTextExtractionService with proper
 * extraction via BeautifulSoup (HTML), python-docx (DOCX), pymupdf (PDF),
 * openpyxl (XLSX), and VLM (images/scanned PDFs).
 *
 * Endpoint: POST /extract-base64
 */
class DocumentExtractionClient(baseUrl: String) {

    private val apiBaseUrl = baseUrl.trimEnd('/')

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                },
            )
        }
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE   // VLM trvá jak trvá, žádný read timeout
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = Long.MAX_VALUE
        }
    }

    /**
     * Extract plain text from string content (HTML, XML, plain text).
     *
     * For plain text content, the service detects type=text and returns as-is (no-op).
     * For HTML/XML, BeautifulSoup extracts text with structure preserved.
     */
    suspend fun extractText(content: String, mimeType: String = "text/html"): String {
        if (content.isBlank()) return content

        val b64 = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        val filename = when {
            mimeType.contains("html") -> "content.html"
            mimeType.contains("xml") -> "content.xml"
            else -> "content.txt"
        }

        val response = client.submitForm(
            url = "$apiBaseUrl/extract-base64",
            formParameters = parameters {
                append("content_base64", b64)
                append("filename", filename)
                append("mime_type", mimeType)
                append("max_tier", "NONE")
            },
        )

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw RuntimeException("document-extraction failed (${response.status}): $errorBody")
        }

        val result: ExtractResponse = response.body()
        logger.info { "Document extraction: ${content.length} chars → ${result.text.length} chars (method=${result.method})" }
        return result.text
    }

    /**
     * Extract plain text from binary file content (PDF, DOCX, XLSX, images).
     */
    suspend fun extractBytes(fileBytes: ByteArray, filename: String, mimeType: String, maxTier: String = "NONE"): String {
        val b64 = Base64.getEncoder().encodeToString(fileBytes)

        val response = client.submitForm(
            url = "$apiBaseUrl/extract-base64",
            formParameters = parameters {
                append("content_base64", b64)
                append("filename", filename)
                append("mime_type", mimeType)
                append("max_tier", maxTier)
            },
        )

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw RuntimeException("document-extraction failed for $filename (${response.status}): $errorBody")
        }

        val result: ExtractResponse = response.body()
        logger.info { "Document extraction: $filename (${fileBytes.size} bytes) → ${result.text.length} chars (method=${result.method})" }
        return result.text
    }
}

@Serializable
data class ExtractResponse(
    val text: String = "",
    val method: String = "",
)
