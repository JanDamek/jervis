package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class AudioMonitoringConfigDto(
    val enabled: Boolean = true,
    val audioPath: String? = null,
    val gitCheckIntervalMinutes: Long? = null,
    val supportedFormats: List<String> = listOf("wav", "mp3", "m4a", "flac", "ogg"),
    val whisperModel: String = "base",
    val whisperLanguage: String? = null,
)
