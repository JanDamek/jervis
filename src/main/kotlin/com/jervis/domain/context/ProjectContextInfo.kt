package com.jervis.domain.context

import kotlinx.serialization.Serializable

@Serializable
data class ProjectContextInfo(
    val projectDescription: String?,
    val techStack: TechStackInfo,
    val codingGuidelines: CodingGuidelines,
    val dependencyInfo: List<String>,
)

@Serializable
data class TaskContextInfo(
    val projectInfo: ProjectContextInfo?,
    val contextSummary: String = "",
)
