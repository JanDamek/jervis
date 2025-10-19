package com.jervis.service

import com.jervis.dto.ProjectDto
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

@HttpExchange("/api/indexing")
interface IIndexingService {
    @PostExchange("/project")
    suspend fun indexProject(
        @RequestBody project: ProjectDto)

    @PostExchange("/all-projects")
    suspend fun indexAllProjects(@RequestBody projects: List<ProjectDto>)

    @PostExchange("/client-projects")
    suspend fun indexProjectsForClient(
        @RequestBody projects: List<ProjectDto>,
        @RequestParam clientName: String,
    )
}
