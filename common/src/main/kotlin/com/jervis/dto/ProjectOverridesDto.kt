package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class ProjectOverridesDto(
    val codingGuidelines: GuidelinesDto? = null,
    val reviewPolicy: ReviewPolicyDto? = null,
    val formatting: FormattingDto? = null,
    val secretsPolicy: SecretsPolicyDto? = null,
    val anonymization: AnonymizationDto? = null,
    val inspirationPolicy: InspirationPolicyDto? = null,
    val tools: ClientToolsDto? = null,
    val audioMonitoring: AudioMonitoringConfigDto? = null,
)
