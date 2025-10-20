package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "jervis.background")
data class BackgroundEngineProperties(
    var enabled: Boolean = true,
    var idleThresholdSeconds: Long = 120,
    var chunkTokenLimit: Int = 1200,
    var chunkTimeoutSeconds: Long = 45,
    var maxCpuBgTasks: Int = 2,
    var coverageWeights: CoverageWeights = CoverageWeights(),
) {
    data class CoverageWeights(
        var docs: Double = 0.3,
        var tasks: Double = 0.2,
        var code: Double = 0.4,
        var meetings: Double = 0.1,
    )
}
