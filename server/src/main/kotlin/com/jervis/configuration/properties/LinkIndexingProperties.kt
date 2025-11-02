package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "link.indexing")
data class LinkIndexingProperties(
    /**
     * Minimum interval between re-indexing the same URL.
     * If a link was indexed recently (within this interval), skip it.
     * Defaults to 30 days.
     */
    val skipIfIndexedWithin: Duration,
)
