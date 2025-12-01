package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ArangoDB configuration properties.
 *
 * Follow JERVIS guidelines: use @ConfigurationProperties and immutable vals.
 */
@ConfigurationProperties(prefix = "arango")
data class ArangoProperties(
    val scheme: String,
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val timeoutMs: Long,
)
