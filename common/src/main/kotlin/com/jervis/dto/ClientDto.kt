package com.jervis.dto

import com.jervis.common.Constants
import com.jervis.domain.language.LanguageEnum
import kotlinx.serialization.Serializable

@Serializable
data class ClientDto(
    val id: String = Constants.GLOBAL_ID_STRING,
    val name: String,
    val shortDescription: String? = null,
    val fullDescription: String? = null,
    val defaultCodingGuidelines: GuidelinesDto = GuidelinesDto(),
    val defaultReviewPolicy: ReviewPolicyDto = ReviewPolicyDto(),
    val defaultFormatting: FormattingDto = FormattingDto(),
    val defaultSecretsPolicy: SecretsPolicyDto = SecretsPolicyDto(),
    val defaultAnonymization: AnonymizationDto = AnonymizationDto(),
    val defaultInspirationPolicy: InspirationPolicyDto = InspirationPolicyDto(),
    val tools: ClientToolsDto = ClientToolsDto(),
    val defaultLanguageEnum: LanguageEnum = LanguageEnum.getDefault(),
    val dependsOnProjects: List<String> = emptyList(),
    val isDisabled: Boolean = false,
    val disabledProjects: List<String> = emptyList(),
)
