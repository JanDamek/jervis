package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "audio.monitoring")
data class AudioMonitoringProperties(
    val defaultGitCheckIntervalMinutes: Long,
    val supportedFormats: List<String>,
)
