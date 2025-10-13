package com.jervis.service

import com.jervis.dto.ProjectDto

interface IIndexingService {
    suspend fun indexProject(project: ProjectDto): Any

    suspend fun indexAllProjects(projects: List<ProjectDto>)

    suspend fun indexProjectsForClient(
        projects: List<ProjectDto>,
        clientName: String,
    )
}
