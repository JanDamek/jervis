package com.jervis.service.gateway

/**
 * Utilities for constructing Ollama API URLs from a user-provided base endpoint.
 * Accepts inputs like:
 *  - http://host:11434
 *  - http://host:11434/
 *  - http://host:11434/api
 *  - http://host:11434/api/
 *  - host:11434 (scheme will default to http)
 */
object OllamaUrl {
    private fun ensureHttpScheme(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    }

    private fun normalizeBase(url: String): String {
        var u = ensureHttpScheme(url).trim()
        // remove trailing slash
        if (u.endsWith('/')) u = u.dropLast(1)
        return u
    }

    /**
     * Build a full Ollama API URL ensuring there is exactly one "/api" segment
     * and exactly one slash between segments.
     *
     * @param base user-provided base URL (may or may not include /api)
     * @param endpointPath API endpoint, e.g., "/version", "/tags", "/generate", "/embeddings" (leading slash optional)
     */
    fun buildApiUrl(
        base: String,
        endpointPath: String,
    ): String {
        val u = normalizeBase(base)
        val ep = if (endpointPath.startsWith("/")) endpointPath else "/$endpointPath"
        return when {
            u.endsWith("/api") -> u + ep
            u.endsWith("/api/") -> u.dropLast(1) + ep // already normalized above, but kept for safety
            else -> "$u/api$ep"
        }
    }
}
