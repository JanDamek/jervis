package com.jervis.common.http

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import mu.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Validate HTTP response from a provider API.
 * Returns the response body text on success (2xx).
 * Throws typed [ProviderApiException] on failure.
 */
suspend fun HttpResponse.checkProviderResponse(provider: String, context: String): String {
    val statusCode = status.value

    if (statusCode in 200..299) {
        return bodyAsText()
    }

    val body = runCatching { bodyAsText() }.getOrNull()
    log.error { "$provider API error ($context): status=$statusCode, body=$body" }

    throw when (statusCode) {
        401, 403 -> ProviderAuthException(statusCode, provider, context, body)
        404 -> ProviderNotFoundException(provider, context, body)
        429 -> {
            val retryAfter = headers["Retry-After"]?.toLongOrNull()
            ProviderRateLimitException(provider, context, retryAfter, body)
        }
        in 500..599 -> ProviderServerException(statusCode, provider, context, body)
        else -> ProviderServerException(statusCode, provider, context, body)
    }
}
