package com.jervis.domain.project

import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.domain.git.GitConfig

data class ProjectOverrides(
    val gitRemoteUrl: String? = null,
    val gitAuthType: GitAuthTypeEnum? = null,
    val gitConfig: GitConfig? = null,
)
