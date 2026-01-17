package com.jervis.repository

import com.jervis.dto.ProjectDto
import com.jervis.service.IProjectService

/**
 * Repository for Project operations
 * Wraps IProjectService with additional logic (caching, error handling, etc.)
 */
class ProjectRepository(
    private val projectService: IProjectService,
) : BaseRepository() {

    /**
     * List all projects for a specific client
     * Filters projects by clientId
     */
    suspend fun listProjectsForClient(clientId: String): List<ProjectDto> =
        safeRpcListCall("listProjectsForClient") {
            val allProjects = projectService.getAllProjects()
            allProjects.filter { it.clientId == clientId }
        }

    /**
     * Get all projects
     */
    suspend fun getAllProjects(): List<ProjectDto> =
        safeRpcListCall("getAllProjects") {
            projectService.getAllProjects()
        }

    /**
     * Save project
     */
    suspend fun saveProject(project: ProjectDto): ProjectDto? =
        safeRpcCall("saveProject", returnNull = true) {
            projectService.saveProject(project)
        }

    /**
     * Update existing project
     */
    suspend fun updateProject(project: ProjectDto): ProjectDto? {
        require(project.id.isNotBlank()) { "Project id must be provided for update" }
        return safeRpcCall("updateProject", returnNull = true) {
            projectService.updateProject(project.id, project)
        }
    }

    /**
     * Delete project
     */
    suspend fun deleteProject(project: ProjectDto) {
        safeRpcCall("deleteProject", returnNull = true) {
            projectService.deleteProject(project)
        }
    }

    /**
     * Get project by name
     */
    suspend fun getProjectByName(name: String?): ProjectDto? =
        safeRpcCall("getProjectByName", returnNull = true) {
            projectService.getProjectByName(name)
        }
}
