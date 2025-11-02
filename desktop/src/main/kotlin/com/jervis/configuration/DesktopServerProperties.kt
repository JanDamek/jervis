package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Binds desktop app configuration for connecting to the server.
 * Source of truth: application.yaml under prefix `jervis.server`.
 */
@Component
@ConfigurationProperties(prefix = "jervis.server")
class DesktopServerProperties {
    var url: String? = null
}
