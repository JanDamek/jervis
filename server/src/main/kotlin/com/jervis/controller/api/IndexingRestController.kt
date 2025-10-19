package com.jervis.controller.api

import com.jervis.dto.ProjectDto
import com.jervis.mapper.toDocument
import com.jervis.service.IIndexingService
import com.jervis.service.indexing.IndexingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.springframework.web.bind.annotation.RestController

@RestController
class IndexingRestController(
    private val indexingService: IndexingService,
) : IIndexingService {
    override suspend fun indexProject(project: ProjectDto) {
        indexingService.indexProject(project.toDocument())
    }

    override suspend fun indexAllProjects(projects: List<ProjectDto>) {
        CoroutineScope(Dispatchers.Default).launch {
            indexingService.indexAllProjects(projects.map { it.toDocument() })
        }
    }

    override suspend fun indexProjectsForClient(
        projects: List<ProjectDto>,
        clientName: String,
    ) {
        CoroutineScope(Dispatchers.Default).launch {
            indexingService.indexProjectsForClient(projects.map { it.toDocument() }, clientName)
        }
    }
}
