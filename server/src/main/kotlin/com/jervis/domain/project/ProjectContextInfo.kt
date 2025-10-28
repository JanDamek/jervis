package com.jervis.domain.project

import com.jervis.domain.client.TechStackInfo

data class ProjectContextInfo(
    val projectDescription: String?,
    val techStack: TechStackInfo,
    val dependencyInfo: List<String>,
)
