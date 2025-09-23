package com.jervis.domain.context

import kotlinx.serialization.Serializable

@Serializable
data class ProjectContextInfo(
    val projectDescription: String?,
    val techStack: TechStackInfo,
    val codingGuidelines: CodingGuidelines,
    val dependencyInfo: List<String>,
)
