package com.jervis.common.ratelimit

import java.net.URI

/**
 * Utility functions for URL/domain extraction.
 */
object UrlUtils {
    /**
     * Extract domain from URL for rate limiting.
     * Examples:
     * - "https://api.atlassian.net/rest/api/3/search" -> "api.atlassian.net"
     * - "http://localhost:8080/api" -> "localhost"
     * - "https://example.com:443/path" -> "example.com"
     */
    fun extractDomain(url: String): String {
        return try {
            val uri = URI(url)
            uri.host ?: "unknown"
        } catch (e: Exception) {
            // Fallback: try to extract domain manually
            url
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore("/")
                .substringBefore(":")
        }
    }

    /**
     * Check if URL is internal/localhost (no rate limiting needed).
     */
    fun isInternalUrl(url: String): Boolean {
        val domain = extractDomain(url)
        return domain.startsWith("localhost") ||
            domain.startsWith("jervis-") ||
            domain.startsWith("127.0.0.1") ||
            domain.startsWith("0.0.0.0") ||
            domain.startsWith("10.") ||
            domain.startsWith("192.168.") ||
            domain.startsWith("172.16.") ||
            domain.startsWith("172.17.") ||
            domain.startsWith("172.18.") ||
            domain.startsWith("172.19.") ||
            domain.startsWith("172.20.") ||
            domain.startsWith("172.21.") ||
            domain.startsWith("172.22.") ||
            domain.startsWith("172.23.") ||
            domain.startsWith("172.24.") ||
            domain.startsWith("172.25.") ||
            domain.startsWith("172.26.") ||
            domain.startsWith("172.27.") ||
            domain.startsWith("172.28.") ||
            domain.startsWith("172.29.") ||
            domain.startsWith("172.30.") ||
            domain.startsWith("172.31.")
    }
}
