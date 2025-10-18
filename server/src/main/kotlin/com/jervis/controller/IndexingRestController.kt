package com.jervis.controller

import com.jervis.dto.ProjectDto
import com.jervis.service.IIndexingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        @RequestBody project: ProjectDto,
    ): Any = indexingService.indexProject(project)

    @PostMapping("/all-projects")
    fun indexAllProjects(
        @RequestBody projects: List<ProjectDto>,
    ): Map<String, String> {
        // Fire-and-forget: start indexing in background and return immediately
        // Progress will be reported via WebSocket notifications
        CoroutineScope(Dispatchers.Default).launch {
            indexingService.indexAllProjects(projects)
        }
        return mapOf("status" to "started", "message" to "Indexing ${projects.size} projects started in background")
    }

    @PostMapping("/client-projects")
    fun indexProjectsForClient(
        @RequestBody projects: List<ProjectDto>,
        @RequestParam clientName: String,
    ): Map<String, String> {
        // Fire-and-forget: start indexing in background and return immediately
        // Progress will be reported via WebSocket notifications
        CoroutineScope(Dispatchers.Default).launch {
            indexingService.indexProjectsForClient(projects, clientName)
        }
        return mapOf(
            "status" to "started",
            "message" to "Indexing ${projects.size} projects for client '$clientName' started in background",
        )
    }
}
