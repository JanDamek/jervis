package com.jervis.domain.language

import kotlinx.serialization.Serializable

/**
 * Enum representing supported communication platforms.
 */
enum class CommunicationPlatform {
    GIT,
    BITBUCKET,
    GITHUB,
    GITLAB,
    TEAMS,
    SLACK,
    CONFLUENCE,
    JIRA,
    EMAIL,
    DISCORD,
    TELEGRAM,
    WHATSAPP,
    SIGNAL,
    MATTERMOST,
    ROCKETCHAT,
}

/**
 * Configuration for language settings on a specific platform.
 */
@Serializable
data class PlatformLanguageSettings(
    val platform: CommunicationPlatform,
    val language: Language,
    val useAutoDetection: Boolean = false,
    val customSettings: Map<String, String> = emptyMap(),
)

/**
 * Complete language configuration for all platforms.
 */
@Serializable
data class PlatformLanguageConfiguration(
    val defaultLanguage: Language = Language.ENGLISH,
    val platformSettings: Map<CommunicationPlatform, PlatformLanguageSettings> = emptyMap(),
) {
    fun getLanguageForPlatform(platform: CommunicationPlatform): Language = platformSettings[platform]?.language ?: defaultLanguage

    fun isAutoDetectionEnabled(platform: CommunicationPlatform): Boolean = platformSettings[platform]?.useAutoDetection ?: false

    fun withPlatformLanguage(
        platform: CommunicationPlatform,
        language: Language,
    ): PlatformLanguageConfiguration {
        val updatedSettings = platformSettings.toMutableMap()
        updatedSettings[platform] =
            PlatformLanguageSettings(
                platform = platform,
                language = language,
                useAutoDetection = platformSettings[platform]?.useAutoDetection ?: false,
                customSettings = platformSettings[platform]?.customSettings ?: emptyMap(),
            )
        return copy(platformSettings = updatedSettings)
    }
}
