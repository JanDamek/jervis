package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class ProjectContextInfoDto(
    val projectDescription: String?,
    val techStack: TechStackInfoDto,
    val dependencyInfo: List<String>,
)
