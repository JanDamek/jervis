package com.jervis.service

import com.jervis.dto.ProjectDto

interface IProjectService {
    suspend fun getAllProjects(): List<ProjectDto>

    suspend fun getDefaultProject(): ProjectDto?

    suspend fun setActiveProject(project: ProjectDto)

    suspend fun setDefaultProject(project: ProjectDto)

    suspend fun saveProject(
        project: ProjectDto,
        makeDefault: Boolean = false,
    ): ProjectDto

    suspend fun deleteProject(project: ProjectDto)

    suspend fun getProjectByName(name: String?): ProjectDto
}
