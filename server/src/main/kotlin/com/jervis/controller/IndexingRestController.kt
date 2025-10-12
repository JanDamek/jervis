package com.jervis.controller

import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.IIndexingService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/indexing")
class IndexingRestController(
    private val indexingService: IIndexingService,
) {
    @PostMapping("/project")
    suspend fun indexProject(
        @RequestBody project: ProjectDocument,
    ): Any = indexingService.indexProject(project)

    @PostMapping("/all-projects")
    suspend fun indexAllProjects(
        @RequestBody projects: List<ProjectDocument>,
    ) {
        indexingService.indexAllProjects(projects)
    }

    @PostMapping("/client-projects")
    suspend fun indexProjectsForClient(
        @RequestBody projects: List<ProjectDocument>,
        @RequestParam clientName: String,
    ) {
        indexingService.indexProjectsForClient(projects, clientName)
    }
}
