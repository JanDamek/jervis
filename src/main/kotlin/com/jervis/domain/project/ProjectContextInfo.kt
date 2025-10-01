package com.jervis.domain.project

import com.jervis.domain.client.CodingGuidelines
import com.jervis.domain.client.TechStackInfo
import kotlinx.serialization.Serializable

@Serializable
data class ProjectContextInfo(
    val projectDescription: String?,
    val techStack: TechStackInfo,
    val codingGuidelines: CodingGuidelines,
    val dependencyInfo: List<String>,
)
