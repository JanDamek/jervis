package com.jervis.common.http

sealed class ProviderApiException(
    val statusCode: Int,
    val provider: String,
    val context: String,
    val responseBody: String? = null,
) : RuntimeException("$provider API error ($context): HTTP $statusCode${responseBody?.let { " - $it" } ?: ""}")

class ProviderAuthException(
    statusCode: Int,
    provider: String,
    context: String,
    responseBody: String? = null,
) : ProviderApiException(statusCode, provider, context, responseBody)

class ProviderNotFoundException(
    provider: String,
    context: String,
    responseBody: String? = null,
) : ProviderApiException(404, provider, context, responseBody)

class ProviderRateLimitException(
    provider: String,
    context: String,
    val retryAfterSeconds: Long? = null,
    responseBody: String? = null,
) : ProviderApiException(429, provider, context, responseBody)

class ProviderServerException(
    statusCode: Int,
    provider: String,
    context: String,
    responseBody: String? = null,
) : ProviderApiException(statusCode, provider, context, responseBody)
