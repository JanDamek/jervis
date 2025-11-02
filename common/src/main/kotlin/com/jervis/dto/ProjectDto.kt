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
    val shortDescription: String? = null,
    val fullDescription: String? = null,
    val monoRepoId: String? = null,
    val projectPath: String? = null,
    val documentationUrls: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val communicationLanguageEnum: LanguageEnum = LanguageEnum.getDefault(),
    val dependsOnProjects: List<String> = emptyList(),
    val inspirationOnly: Boolean = false,
    val indexingRules: IndexingRulesDto = IndexingRulesDto(),
    val isDisabled: Boolean = false,
    val isActive: Boolean = false,
    val overrides: ProjectOverridesDto? = null,
)
