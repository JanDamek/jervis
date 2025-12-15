package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Configuration for Netty server timeouts.
 *
 * Controls timeout settings for the embedded Netty server.
 */
@Component
@ConfigurationProperties(prefix = "jervis.netty")
data class NettyProperties(
    /**
     * ConnectionDocument timeout in milliseconds.
     * Default: 60000ms (60 seconds)
     */
    var connectTimeoutMs: Int = 60000,
    /**
     * Idle timeout in seconds.
     * Default: 600 seconds (10 minutes)
     */
    var idleTimeoutSeconds: Long = 600,
    /**
     * Read timeout in seconds.
     * Default: 600 seconds (10 minutes)
     */
    var readTimeoutSeconds: Long = 600,
    /**
     * Write timeout in seconds.
     * Default: 600 seconds (10 minutes)
     */
    var writeTimeoutSeconds: Long = 600,
    /**
     * Whether to enable SO_KEEPALIVE.
     * Default: true
     */
    var soKeepalive: Boolean = true,
)
