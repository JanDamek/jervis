package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "audio.whisper")
class AudioWhisperProperties(
    var apiUrl: String = "http://localhost:9000/",
    var timeoutSeconds: Long = 300,
    var maxRetries: Int = 3,
)
