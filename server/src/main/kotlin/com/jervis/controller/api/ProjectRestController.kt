package com.jervis.controller.api

import com.jervis.dto.ProjectDto
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.service.IProjectService
import com.jervis.service.project.ProjectService
import org.springframework.web.bind.annotation.RestController

@RestController
class ProjectRestController(
    private val projectService: ProjectService,
) : IProjectService {
    override suspend fun getAllProjects(): List<ProjectDto> = projectService.getAllProjects().map { it.toDto() }

    override suspend fun getDefaultProject(): ProjectDto? = projectService.getDefaultProject()?.toDto()

    override suspend fun setActiveProject(project: ProjectDto) {
        projectService.setActiveProject(project.toDocument())
    }

    override suspend fun setDefaultProject(project: ProjectDto) {
        projectService.setDefaultProject(project.toDocument())
    }

    override suspend fun saveProject(
        project: ProjectDto,
        makeDefault: Boolean,
    ): ProjectDto = projectService.saveProject(project.toDocument(), makeDefault)

    override suspend fun deleteProject(project: ProjectDto) {
        projectService.deleteProject(project)
    }

    override suspend fun getProjectByName(name: String?): ProjectDto = projectService.getProjectByName(name).toDto()
}
