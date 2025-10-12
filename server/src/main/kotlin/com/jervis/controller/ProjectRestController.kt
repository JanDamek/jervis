package com.jervis.controller

import com.jervis.entity.mongo.ProjectDocument
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
    private val projectService: IProjectService,
) {
    @GetMapping
    suspend fun getAllProjects(): List<ProjectDocument> = projectService.getAllProjects()

    @GetMapping("/default")
    suspend fun getDefaultProject(): ProjectDocument? = projectService.getDefaultProject()

    @PutMapping("/active")
    suspend fun setActiveProject(
        @RequestBody project: ProjectDocument,
    ) {
        projectService.setActiveProject(project)
    }

    @PutMapping("/default")
    suspend fun setDefaultProject(
        @RequestBody project: ProjectDocument,
    ) {
        projectService.setDefaultProject(project)
    }

    @PostMapping
    suspend fun saveProject(
        @RequestBody project: ProjectDocument,
        @RequestParam(required = false, defaultValue = "false") makeDefault: Boolean,
    ): ProjectDocument = projectService.saveProject(project, makeDefault)

    @DeleteMapping
    suspend fun deleteProject(
        @RequestBody project: ProjectDocument,
    ) {
        projectService.deleteProject(project)
    }

    @GetMapping("/by-name")
    suspend fun getProjectByName(
        @RequestParam name: String,
    ): ProjectDocument = projectService.getProjectByName(name)
}
