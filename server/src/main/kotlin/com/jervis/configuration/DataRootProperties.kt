package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Root directory configuration for server-managed data.
 * Can be configured via:
 * - application.yml: data.root-dir=/path/to/data
 * - Environment variable: DATA_ROOT_DIR=/path/to/data
 * - System property: -Ddata.root.dir=/path/to/data
 */
@ConfigurationProperties(prefix = "data")
data class DataRootProperties(
    val rootDir: String = "./data",
)
