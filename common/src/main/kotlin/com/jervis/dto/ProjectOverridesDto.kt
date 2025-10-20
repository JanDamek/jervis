package com.jervis.dto

import com.jervis.domain.git.GitAuthTypeEnum
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
    val gitRemoteUrl: String? = null,
    val gitAuthType: GitAuthTypeEnum? = null,
    val gitConfig: GitConfigDto? = null,
    val gitCredentials: GitCredentialsDto? = null,
)
