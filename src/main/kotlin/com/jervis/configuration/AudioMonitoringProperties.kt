package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "audio.monitoring")
class AudioMonitoringProperties(
    @DefaultValue("15")
    var defaultGitCheckIntervalMinutes: Long = 15,
    @DefaultValue("wav,mp3,m4a,flac,ogg,opus,webm")
    var supportedFormats: List<String> = listOf("wav", "mp3", "m4a", "flac", "ogg", "opus", "webm"),
)
