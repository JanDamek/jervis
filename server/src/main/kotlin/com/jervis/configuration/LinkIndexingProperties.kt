package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "link.indexing")
data class LinkIndexingProperties(
    /**
     * Minimum interval between re-indexing the same URL.
     * If link was indexed recently (within this interval), skip it.
     * Defaults to 30 days.
     */
    val skipIfIndexedWithin: Duration = Duration.ofDays(30),
)

@ConfigurationProperties(prefix = "link.safety")
data class LinkSafetyProperties(
    /**
     * Enable LLM-based link qualification for uncertain links.
     * If false, only pattern-based filtering is used.
     * Defaults to true.
     */
    val enableLlmQualification: Boolean = true,
    /**
     * Conservative mode: block uncertain links.
     * If false, allows uncertain links to be indexed.
     * Defaults to true.
     */
    val blockUncertain: Boolean = true,
)
