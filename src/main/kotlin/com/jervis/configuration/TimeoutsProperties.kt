package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "timeouts")
class TimeoutsProperties {
    var joern: JoernTimeouts = JoernTimeouts()
    var webclient: WebClientTimeouts = WebClientTimeouts()
    var qdrant: QdrantTimeouts = QdrantTimeouts()
    var mcp: McpTimeouts = McpTimeouts()

    data class JoernTimeouts(
        var processTimeoutMinutes: Long = 30,
        var helpCommandTimeoutMinutes: Long = 30,
        var scanTimeoutMinutes: Long = 30,
        var scriptTimeoutMinutes: Long = 30,
        var parseTimeoutMinutes: Long = 30,
        var versionTimeoutMinutes: Long = 30,
    )

    data class WebClientTimeouts(
        var connectTimeoutSeconds: Long = 60,
        var responseTimeoutSeconds: Long = 120,
        var readTimeoutSeconds: Long = 60,
        var writeTimeoutSeconds: Long = 60,
        var pendingAcquireTimeoutMinutes: Long = 5,
    )

    data class QdrantTimeouts(
        var operationTimeoutMinutes: Long = 30,
    )

    data class McpTimeouts(
        var joernToolTimeoutSeconds: Long = 1800,
        var terminalToolTimeoutSeconds: Long = 1800,
    )
}
