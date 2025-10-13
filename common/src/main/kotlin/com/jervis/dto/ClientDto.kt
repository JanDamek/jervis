package com.jervis.dto

import com.jervis.domain.client.Anonymization
import com.jervis.domain.client.ClientTools
import com.jervis.domain.client.Formatting
import com.jervis.domain.client.Guidelines
import com.jervis.domain.client.InspirationPolicy
import com.jervis.domain.client.ReviewPolicy
import com.jervis.domain.client.SecretsPolicy
import com.jervis.domain.language.Language
import kotlinx.serialization.Serializable

@Serializable
data class ClientDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val shortDescription: String? = null,
    val fullDescription: String? = null,
    val defaultCodingGuidelines: Guidelines = Guidelines(),
    val defaultReviewPolicy: ReviewPolicy = ReviewPolicy(),
    val defaultFormatting: Formatting = Formatting(),
    val defaultSecretsPolicy: SecretsPolicy = SecretsPolicy(),
    val defaultAnonymization: Anonymization = Anonymization(),
    val defaultInspirationPolicy: InspirationPolicy = InspirationPolicy(),
    val tools: ClientTools = ClientTools(),
    val defaultLanguage: Language = Language.getDefault(),
    val dependsOnProjects: List<String> = emptyList(),
    val isDisabled: Boolean = false,
    val disabledProjects: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)
