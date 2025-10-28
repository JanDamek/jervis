package com.jervis.dto

import com.jervis.domain.git.GitAuthTypeEnum
import kotlinx.serialization.Serializable

@Serializable
data class ProjectOverridesDto(
    val gitRemoteUrl: String? = null,
    val gitAuthType: GitAuthTypeEnum? = null,
    val gitConfig: GitConfigDto? = null,
)
