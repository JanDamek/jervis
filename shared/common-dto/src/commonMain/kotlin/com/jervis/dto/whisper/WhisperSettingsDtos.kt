package com.jervis.dto.whisper

import kotlinx.serialization.Serializable

/**
 * Available Whisper model sizes.
 * Larger models are more accurate but slower and require more memory.
 */
@Serializable
enum class WhisperModelSize(val displayName: String, val description: String) {
    TINY("tiny", "Nejrychlejší, nejméně přesný (~1 GB RAM)"),
    BASE("base", "Dobrý poměr rychlost/kvalita (~1 GB RAM)"),
    SMALL("small", "Lepší přesnost (~2 GB RAM)"),
    MEDIUM("medium", "Vysoká přesnost (~5 GB RAM)"),
    LARGE_V3("large-v3", "Nejvyšší přesnost (~10 GB RAM)"),
}

/**
 * Whisper task type.
 */
@Serializable
enum class WhisperTask(val displayName: String) {
    TRANSCRIBE("Přepis (zachová jazyk)"),
    TRANSLATE("Překlad do angličtiny"),
}

/**
 * Whisper deployment mode.
 * K8S_JOB: Runs as a short-lived K8s Job (requires sufficient cluster RAM).
 * REST_REMOTE: Calls a persistent Whisper REST service on a remote host.
 */
@Serializable
enum class WhisperDeploymentMode(val displayName: String, val description: String) {
    K8S_JOB("Kubernetes Job", "Spouští se jako K8s Job v clusteru (vyžaduje dostatek RAM)"),
    REST_REMOTE("Vzdálený REST server", "Volá vzdálený Whisper server přes REST API"),
}

/**
 * Global Whisper transcription settings.
 * Singleton document – one configuration for the entire system.
 */
@Serializable
data class WhisperSettingsDto(
    /** Whisper model size */
    val model: WhisperModelSize = WhisperModelSize.BASE,
    /** Task: transcribe or translate to English */
    val task: WhisperTask = WhisperTask.TRANSCRIBE,
    /** Language code (null = auto-detect). ISO 639-1, e.g. "cs", "en", "de" */
    val language: String? = null,
    /** Beam size for decoding (1-10). Higher = more accurate but slower */
    val beamSize: Int = 5,
    /** Enable Silero VAD filter to skip silence – significantly speeds up processing */
    val vadFilter: Boolean = true,
    /** Enable word-level timestamps in segments */
    val wordTimestamps: Boolean = false,
    /** Use previous text as context for next segment (can degrade on very long audio) */
    val conditionOnPreviousText: Boolean = true,
    /** No-speech probability threshold (0.0-1.0) */
    val noSpeechThreshold: Double = 0.6,
    /** Max parallel Whisper K8s Jobs */
    val maxParallelJobs: Int = 3,
    /** Timeout multiplier relative to audio duration (e.g. 3 = 3x audio length) */
    val timeoutMultiplier: Int = 3,
    /** Minimum timeout in seconds */
    val minTimeoutSeconds: Int = 600,
    /** Deployment mode: K8s Job or remote REST service */
    val deploymentMode: WhisperDeploymentMode = WhisperDeploymentMode.K8S_JOB,
    /** Remote Whisper REST server URL (used when deploymentMode = REST_REMOTE) */
    val restRemoteUrl: String = "http://192.168.100.117:8786",
)

/**
 * Request DTO for updating Whisper settings.
 */
@Serializable
data class WhisperSettingsUpdateDto(
    val model: WhisperModelSize? = null,
    val task: WhisperTask? = null,
    val language: String? = null,
    val clearLanguage: Boolean = false,
    val beamSize: Int? = null,
    val vadFilter: Boolean? = null,
    val wordTimestamps: Boolean? = null,
    val conditionOnPreviousText: Boolean? = null,
    val noSpeechThreshold: Double? = null,
    val maxParallelJobs: Int? = null,
    val timeoutMultiplier: Int? = null,
    val minTimeoutSeconds: Int? = null,
    val deploymentMode: WhisperDeploymentMode? = null,
    val restRemoteUrl: String? = null,
)
