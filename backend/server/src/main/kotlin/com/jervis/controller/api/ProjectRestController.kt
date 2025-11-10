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

    @GetMapping("/default")
    override suspend fun getDefaultProject(): ProjectDto? = projectService.getDefaultProject()?.toDto()

    @PutMapping("/active")
    override suspend fun setActiveProject(@RequestBody project: ProjectDto) {
        projectService.setActiveProject(project.toDocument())
    }

    @PutMapping("/default")
    override suspend fun setDefaultProject(@RequestBody project: ProjectDto) {
        projectService.setDefaultProject(project.toDocument())
    }

    @PostMapping
    override suspend fun saveProject(
        @RequestBody project: ProjectDto,
        @RequestParam(defaultValue = "false") makeDefault: Boolean,
    ): ProjectDto = projectService.saveProject(project.toDocument(), makeDefault)

    @DeleteMapping
    override suspend fun deleteProject(@RequestBody project: ProjectDto) {
        projectService.deleteProject(project)
    }

    @GetMapping("/by-name")
    override suspend fun getProjectByName(@RequestParam name: String?): ProjectDto = projectService.getProjectByName(name).toDto()
}
