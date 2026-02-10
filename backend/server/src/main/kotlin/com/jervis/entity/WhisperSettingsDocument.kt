package com.jervis.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Singleton MongoDB document for global Whisper transcription settings.
 * Only one document with [SINGLETON_ID] should exist.
 */
@Document(collection = "whisper_settings")
data class WhisperSettingsDocument(
    @Id
    val id: String = SINGLETON_ID,
    val model: String = "base",
    val task: String = "transcribe",
    /** ISO 639-1 language code, null = auto-detect */
    val language: String? = null,
    val beamSize: Int = 5,
    val vadFilter: Boolean = true,
    val wordTimestamps: Boolean = false,
    val conditionOnPreviousText: Boolean = true,
    val noSpeechThreshold: Double = 0.6,
    val maxParallelJobs: Int = 3,
    val timeoutMultiplier: Int = 3,
    val minTimeoutSeconds: Int = 600,
    /** Deployment mode: "k8s_job" or "rest_remote" */
    val deploymentMode: String = "k8s_job",
    /** Remote Whisper REST server URL (used when deploymentMode = "rest_remote") */
    val restRemoteUrl: String = "http://192.168.100.117:8786",
) {
    companion object {
        const val SINGLETON_ID = "whisper-global"
    }
}
