package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Global configuration for continuous indexing Flow behavior.
 *
 * bufferSize controls the buffer between the producer (state manager polling) and the processing pipeline.
 * It does NOT delay first items; it only decouples fast producers from slower consumers.
 */
@ConfigurationProperties(prefix = "jervis.indexing.flow")
data class IndexingFlowProperties(
    val bufferSize: Int = 128,
)
