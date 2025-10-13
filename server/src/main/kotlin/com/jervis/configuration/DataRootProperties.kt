package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Root directory configuration for server-managed data.
 * Example: data.root-dir=/var/lib/jervis
 */
@ConfigurationProperties(prefix = "data")
data class DataRootProperties(
    val rootDir: String,
)
