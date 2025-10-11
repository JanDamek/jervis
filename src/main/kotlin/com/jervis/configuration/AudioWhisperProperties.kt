package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "audio.whisper")
class AudioWhisperProperties(
    @DefaultValue("http://localhost:9000/")
    var apiUrl: String = "http://localhost:9000/",
    @DefaultValue("300")
    var timeoutSeconds: Long = 300,
    @DefaultValue("3")
    var maxRetries: Int = 3,
    @DefaultValue("false")
    var enabled: Boolean = false,
)
