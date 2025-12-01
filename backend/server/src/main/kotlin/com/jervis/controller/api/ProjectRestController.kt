package com.jervis.controller.api

import com.jervis.dto.ProjectDto
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.service.IProjectService
import com.jervis.service.project.ProjectService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/projects")
class ProjectRestController(
    private val projectService: ProjectService,
) : IProjectService {
    @GetMapping
    override suspend fun getAllProjects(): List<ProjectDto> = projectService.getAllProjects().map { it.toDto() }

    @PostMapping
    override suspend fun saveProject(@RequestBody project: ProjectDto): ProjectDto = 
        projectService.saveProject(project.toDocument())

    @DeleteMapping
    override suspend fun deleteProject(@RequestBody project: ProjectDto) {
        projectService.deleteProject(project)
    }

    @GetMapping("/by-name")
    override suspend fun getProjectByName(@RequestParam name: String?): ProjectDto = projectService.getProjectByName(name).toDto()
}
