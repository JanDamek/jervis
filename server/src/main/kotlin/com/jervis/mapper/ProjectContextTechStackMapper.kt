package com.jervis.mapper

import com.jervis.domain.project.ProjectContextInfo
import com.jervis.dto.ProjectContextInfoDto

fun ProjectContextInfoDto.toDomain() =
    ProjectContextInfo(
        projectDescription = projectDescription,
        techStack = techStack.toDomain(),
        codingGuidelines = codingGuidelines.toDomain(),
        dependencyInfo = dependencyInfo,
    )

fun ProjectContextInfo.toDto() =
    ProjectContextInfoDto(
        projectDescription = projectDescription,
        techStack = techStack.toDto(),
        codingGuidelines = codingGuidelines.toDto(),
        dependencyInfo = dependencyInfo,
    )
