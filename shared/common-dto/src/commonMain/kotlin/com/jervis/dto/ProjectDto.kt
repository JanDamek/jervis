package com.jervis.dto

import com.jervis.common.Constants
import com.jervis.domain.language.LanguageEnum
import kotlinx.serialization.Serializable

@Serializable
data class ProjectDto(
    val id: String = Constants.GLOBAL_ID_STRING,
    val clientId: String?,
    val name: String,
    val description: String? = null,
    val communicationLanguageEnum: LanguageEnum = LanguageEnum.getDefault(),
    // External service connections assigned to this project (exclusive ownership invariant)
    val connectionIds: List<String> = emptyList(),
)
