package com.jervis.service.http

import com.jervis.service.ratelimit.DomainRateLimiterService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.util.AttributeKey

/**
 * Ktor client plugin for automatic domain-based rate limiting.
 *
 * This plugin intercepts all HTTP requests and applies rate limiting
 * based on the target domain before the request is sent.
 *
 * Features:
 * - Automatic rate limiting (no manual acquirePermit calls needed)
 * - Non-blocking (uses suspend delay)
 * - Domain-based (shares limits across Confluence + Jira)
 * - Internal IPs exempt from rate limiting
 *
 * Usage:
 * ```kotlin
 * val client = HttpClient(CIO) {
 *     install(RateLimitingPlugin) {
 *         rateLimiter = domainRateLimiterService
 *     }
 * }
 * ```
 */
class RateLimitingPluginConfig {
    lateinit var rateLimiter: DomainRateLimiterService
}

val RateLimitingPlugin = createClientPlugin("RateLimitingPlugin", ::RateLimitingPluginConfig) {
    val rateLimiter = pluginConfig.rateLimiter

    // Intercept requests in the Before phase (before sending)
    client.requestPipeline.intercept(HttpRequestPipeline.Before) {
        val url = context.url.toString()

        // Apply rate limiting before request execution
        rateLimiter.acquirePermit(url)
    }
}
