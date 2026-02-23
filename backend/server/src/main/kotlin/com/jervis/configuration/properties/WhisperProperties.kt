package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "whisper")
data class WhisperProperties(
    /** Deployment mode: "k8s_job" or "rest_remote" */
    val deploymentMode: String = "rest_remote",
    /** Remote Whisper REST server URL (used when deploymentMode = "rest_remote") */
    val restRemoteUrl: String = "http://192.168.100.117:8786",
    /** Model size: tiny, base, small, medium, large-v3 */
    val model: String = "medium",
    /** Beam search size (1-10, higher = more accurate but slower) */
    val beamSize: Int = 8,
    /** Enable Voice Activity Detection to skip silence */
    val vadFilter: Boolean = true,
    /** Word-level timestamps (rarely needed) */
    val wordTimestamps: Boolean = false,
    /** Use previous text as context for next segment */
    val conditionOnPreviousText: Boolean = true,
    /** Threshold for no-speech probability (0.0-1.0) */
    val noSpeechThreshold: Double = 0.6,
    /** Maximum parallel transcription jobs */
    val maxParallelJobs: Int = 5,
    /** Timeout = audio duration * multiplier */
    val timeoutMultiplier: Int = 3,
    /** Minimum timeout in seconds */
    val minTimeoutSeconds: Int = 600,
)
