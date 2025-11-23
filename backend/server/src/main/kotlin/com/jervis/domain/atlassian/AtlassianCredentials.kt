package com.jervis.domain.atlassian

import java.util.Base64

/**
 * Value object representing Atlassian API credentials.
 * Atlassian uses API token with Basic Auth (email + API token).
 */
data class AtlassianCredentials(
    val tenant: String,      // example.atlassian.net
    val email: String,       // user@company.com
    val apiToken: String,    // API token from Atlassian
) {
    init {
        require(tenant.isNotBlank()) { "Tenant must not be blank" }
        require(email.isNotBlank()) { "Email must not be blank" }
        require(apiToken.isNotBlank()) { "API token must not be blank" }
    }

    /**
     * Convert to Basic Auth header value.
     * Format: "Basic base64(email:apiToken)"
     */
    fun toBasicAuthHeader(): String =
        "Basic " + Base64.getEncoder().encodeToString("$email:$apiToken".toByteArray())

    /**
     * Normalize tenant to hostname only (remove protocol, trailing slash).
     */
    fun normalized(): AtlassianCredentials = copy(
        tenant = tenant
            .trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/")
    )
}
