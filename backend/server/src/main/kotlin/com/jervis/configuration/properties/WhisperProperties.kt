package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "whisper")
data class WhisperProperties(
    /** Remote Whisper REST server URL */
    val restRemoteUrl: String = "http://ollama.damek.local:8786",
    /** Model size: large-v3 for max accuracy */
    val model: String = "large-v3",
    /** Beam search size (10 = max accuracy) */
    val beamSize: Int = 10,
    /** Enable Voice Activity Detection to skip silence */
    val vadFilter: Boolean = true,
    /** Use previous text as context for next segment */
    val conditionOnPreviousText: Boolean = true,
    /** Threshold for no-speech probability (lower = more sensitive) */
    val noSpeechThreshold: Double = 0.3,
    /** Enable speaker diarization (pyannote) */
    val diarize: Boolean = true,
    /** Timeout = audio duration * multiplier */
    val timeoutMultiplier: Int = 5,
    /** Minimum timeout in seconds */
    val minTimeoutSeconds: Int = 600,
)
