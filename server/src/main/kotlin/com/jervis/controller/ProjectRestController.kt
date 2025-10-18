package com.jervis.controller

import com.jervis.dto.ProjectDto
import com.jervis.service.IProjectService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/projects")
class ProjectRestController(
    private val projectService: com.jervis.service.project.ProjectService,
) : IProjectService {
    @GetMapping
    override suspend fun getAllProjects(): List<ProjectDto> = projectService.getAllProjects()

    @GetMapping("/default")
    override suspend fun getDefaultProject(): ProjectDto? = projectService.getDefaultProject()

    @PutMapping("/active")
    override suspend fun setActiveProject(
        @RequestBody project: ProjectDto,
    ) {
        projectService.setActiveProject(project)
    }

    @PutMapping("/default")
    override suspend fun setDefaultProject(
        @RequestBody project: ProjectDto,
    ) {
        projectService.setDefaultProject(project)
    }

    @PostMapping
    override suspend fun saveProject(
        @RequestBody project: ProjectDto,
        @RequestParam(required = false, defaultValue = "false") makeDefault: Boolean,
    ): ProjectDto = projectService.saveProject(project, makeDefault)

    @DeleteMapping
    override suspend fun deleteProject(
        @RequestBody project: ProjectDto,
    ) {
        projectService.deleteProject(project)
    }

    @GetMapping("/by-name")
    override suspend fun getProjectByName(
        @RequestParam(required = false) name: String?,
    ): ProjectDto = projectService.getProjectByName(name)
}
