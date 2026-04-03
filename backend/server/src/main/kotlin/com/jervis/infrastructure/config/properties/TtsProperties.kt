package com.jervis.infrastructure.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "tts")
data class TtsProperties(
    /** XTTS v2 GPU service URL on VD */
    val url: String = "http://ollama.lan.mazlusek.com:8787",
    /** Speaking speed multiplier (1.0 = normal) */
    val speed: Float = 1.0f,
    /** Path to speaker reference WAV on VD for voice cloning (null = default XTTS voice) */
    val speakerWav: String? = null,
    /** Language for XTTS v2 (cs = Czech) */
    val language: String = "cs",
)
