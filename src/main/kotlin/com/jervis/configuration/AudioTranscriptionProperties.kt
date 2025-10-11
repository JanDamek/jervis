package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "audio.transcription")
class AudioTranscriptionProperties(
    @DefaultValue("base")
    var model: String = "base",
    var language: String? = null,
)
