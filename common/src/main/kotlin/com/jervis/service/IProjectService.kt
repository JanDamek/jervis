package com.jervis.service

import com.jervis.dto.ProjectDto
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.DeleteExchange
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import org.springframework.web.service.annotation.PutExchange

@HttpExchange("/api/projects")
interface IProjectService {
    @GetExchange
    suspend fun getAllProjects(): List<ProjectDto>

    @GetExchange("/default")
    suspend fun getDefaultProject(): ProjectDto?

    @PutExchange("/active")
    suspend fun setActiveProject(@RequestBody project: ProjectDto)

    @PutExchange("/default")
    suspend fun setDefaultProject(@RequestBody project: ProjectDto)

    @PostExchange
    suspend fun saveProject(
        @RequestBody project: ProjectDto,
        @RequestParam(required = false, defaultValue = "false") makeDefault: Boolean = false,
    ): ProjectDto

    @DeleteExchange
    suspend fun deleteProject(@RequestBody project: ProjectDto)

    @GetExchange("/by-name")
    suspend fun getProjectByName(@RequestParam(required = false) name: String?): ProjectDto
}
