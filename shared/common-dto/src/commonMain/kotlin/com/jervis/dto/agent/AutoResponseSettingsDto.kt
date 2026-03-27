package com.jervis.dto.agent

import kotlinx.serialization.Serializable

@Serializable
data class AutoResponseSettingsDto(
    val id: String? = null,
    val clientId: String? = null,
    val projectId: String? = null,
    val channelType: String? = null,
    val channelId: String? = null,
    val enabled: Boolean = false,
    val neverAutoResponse: Boolean = false,
    val responseRules: List<ResponseRuleDto> = emptyList(),
    val learningEnabled: Boolean = true,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class ResponseRuleDto(
    val trigger: String,
    val action: String,
)

@Serializable
data class AutoResponseDecisionDto(
    val enabled: Boolean,
    val blocked: Boolean,
    val resolvedLevel: String,
    val rules: List<ResponseRuleDto>,
)
