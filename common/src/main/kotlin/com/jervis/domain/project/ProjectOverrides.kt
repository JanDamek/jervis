package com.jervis.domain.project

import com.jervis.domain.client.Anonymization
import com.jervis.domain.client.ClientTools
import com.jervis.domain.client.Formatting
import com.jervis.domain.client.Guidelines
import com.jervis.domain.client.InspirationPolicy
import com.jervis.domain.client.ReviewPolicy
import com.jervis.domain.client.SecretsPolicy
import kotlinx.serialization.Serializable

@Serializable
data class AudioMonitoringConfig(
    val enabled: Boolean = true,
    val gitCheckIntervalMinutes: Long? = null,
    val supportedFormats: List<String> = listOf("wav", "mp3", "m4a", "flac", "ogg"),
    val whisperModel: String = "base",
    val whisperLanguage: String? = null,
)

@Serializable
data class ProjectOverrides(
    val codingGuidelines: Guidelines? = null,
    val reviewPolicy: ReviewPolicy? = null,
    val formatting: Formatting? = null,
    val secretsPolicy: SecretsPolicy? = null,
    val anonymization: Anonymization? = null,
    val inspirationPolicy: InspirationPolicy? = null,
    val tools: ClientTools? = null,
    val audioMonitoring: AudioMonitoringConfig? = null,
)
