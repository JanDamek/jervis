package com.jervis.infrastructure.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "tts")
data class TtsProperties(
    /** XTTS v2 GPU service URL on VD */
    val url: String = "http://ollama.lan.mazlusek.com:8787",
    /** Speaking speed multiplier (1.0 = normal, 1.2 = slightly faster) */
    val speed: Float = 1.2f,
)
