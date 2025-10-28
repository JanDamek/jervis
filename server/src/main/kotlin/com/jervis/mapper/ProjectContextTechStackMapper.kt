package com.jervis.mapper

import com.jervis.domain.project.ProjectContextInfo
import com.jervis.dto.ProjectContextInfoDto

fun ProjectContextInfoDto.toDomain() =
    ProjectContextInfo(
        projectDescription = projectDescription,
        techStack = techStack.toDomain(),
        dependencyInfo = dependencyInfo,
    )

fun ProjectContextInfo.toDto() =
    ProjectContextInfoDto(
        projectDescription = projectDescription,
        techStack = techStack.toDto(),
        dependencyInfo = dependencyInfo,
    )
