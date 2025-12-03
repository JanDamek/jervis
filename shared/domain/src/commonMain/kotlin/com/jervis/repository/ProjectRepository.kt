package com.jervis.repository

import com.jervis.dto.ProjectDto
import com.jervis.service.IProjectService

/**
 * Repository for Project operations
 * Wraps IProjectService with additional logic (caching, error handling, etc.)
 */
class ProjectRepository(
    private val projectService: IProjectService
) {

    /**
     * List all projects for a specific client
     * Filters projects by clientId
     */
    suspend fun listProjectsForClient(clientId: String): List<ProjectDto> {
        val allProjects = projectService.getAllProjects()
        return allProjects.filter { it.clientId == clientId }
    }

    /**
     * Get all projects
     */
    suspend fun getAllProjects(): List<ProjectDto> {
        return projectService.getAllProjects()
    }

    /**
     * Save project
     */
    suspend fun saveProject(project: ProjectDto): ProjectDto {
        return projectService.saveProject(project)
    }

    /**
     * Update existing project via PUT /api/projects/{id}
     * Uses the project's id as path parameter and sends full body for update.
     */
    suspend fun updateProject(project: ProjectDto): ProjectDto {
        require(project.id.isNotBlank()) { "Project id must be provided for update" }
        return projectService.updateProject(project.id, project)
    }

    /**
     * Delete project
     */
    suspend fun deleteProject(project: ProjectDto) {
        projectService.deleteProject(project)
    }

    /**
     * Get project by name
     */
    suspend fun getProjectByName(name: String?): ProjectDto {
        return projectService.getProjectByName(name)
    }
}
