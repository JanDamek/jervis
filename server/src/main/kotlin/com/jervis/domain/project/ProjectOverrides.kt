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
