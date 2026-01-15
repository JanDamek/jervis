package com.jervis.rpc

import com.jervis.dto.ProjectDto
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.service.IProjectService
import com.jervis.service.project.ProjectService
import org.springframework.stereotype.Service

@Service
class ProjectRpcImpl(
    private val projectService: ProjectService
) : IProjectService {

    override suspend fun getAllProjects(): List<ProjectDto> =
        projectService.getAllProjects().map { it.toDto() }

    override suspend fun saveProject(project: ProjectDto): ProjectDto =
        projectService.saveProject(project.toDocument())

    override suspend fun updateProject(id: String, project: ProjectDto): ProjectDto =
        projectService.saveProject(project.copy(id = id).toDocument())

    override suspend fun deleteProject(project: ProjectDto) {
        projectService.deleteProject(project)
    }

    override suspend fun getProjectByName(name: String?): ProjectDto =
        projectService.getProjectByName(name).toDto()
}
