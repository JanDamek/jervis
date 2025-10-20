package com.jervis.service

import com.jervis.dto.ProjectDto
import com.jervis.dto.ProjectGitOverrideRequestDto
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

/**
 * HTTP Exchange interface for project Git configuration operations.
 * Allows desktop client to configure project-level Git overrides.
 */
@HttpExchange("/api/v1/git")
interface IProjectGitConfigurationService {
    /**
     * Setup Git override configuration for a project.
     * Allows projects to use different credentials, remote URL, and auth type than the client.
     *
     * @param projectId The project ID
     * @param request The Git override configuration including credentials
     * @return Updated ProjectDto with new Git overrides
     */
    @PostExchange("/projects/{projectId}/setup-override")
    suspend fun setupGitOverrideForProject(
        @PathVariable projectId: String,
        @RequestBody request: ProjectGitOverrideRequestDto,
    ): ProjectDto
}
