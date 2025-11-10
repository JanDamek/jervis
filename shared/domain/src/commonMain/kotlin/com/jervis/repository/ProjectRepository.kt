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
     * Get default project
     */
    suspend fun getDefaultProject(): ProjectDto? {
        return projectService.getDefaultProject()
    }

    /**
     * Set active project
     */
    suspend fun setActiveProject(project: ProjectDto) {
        projectService.setActiveProject(project)
    }

    /**
     * Set default project
     */
    suspend fun setDefaultProject(project: ProjectDto) {
        projectService.setDefaultProject(project)
    }

    /**
     * Save project
     */
    suspend fun saveProject(project: ProjectDto, makeDefault: Boolean = false): ProjectDto {
        return projectService.saveProject(project, makeDefault)
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
