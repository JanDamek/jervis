package com.jervis.service

import com.jervis.dto.ClientDto
import com.jervis.dto.CloneResultDto
import com.jervis.dto.GitCredentialsDto
import com.jervis.dto.GitSetupRequestDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ProjectGitOverrideRequestDto
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

/**
 * HTTP Exchange interface for Git configuration operations.
 * Used by desktop client to configure Git settings for clients and projects.
 * Handles both client-level and project-level Git configuration including credentials.
 */
@HttpExchange("/api/v1/git")
interface IGitConfigurationService {
    /**
     * Sets up complete Git configuration for a client including credentials.
     * Credentials are encrypted and stored directly in ClientDocument.
     *
     * @param clientId The client ID
     * @param request Complete Git setup including provider, auth type, credentials, and config
     * @return Updated ClientDto with Git configuration
     */
    @PostExchange("/clients/{clientId}/setup")
    suspend fun setupGitConfiguration(
        @PathVariable clientId: String,
        @RequestBody request: GitSetupRequestDto,
    ): ClientDto

    /**
     * Test Git repository connection with provided credentials.
     * Validates that the repository is accessible before saving configuration.
     *
     * @param clientId The client ID
     * @param request Git setup request with credentials to test
     * @return ResponseEntity with success status and message
     */
    @PostExchange("/clients/{clientId}/test-connection")
    suspend fun testConnection(
        @PathVariable clientId: String,
        @RequestBody request: GitSetupRequestDto,
    ): ResponseEntity<Map<String, Any>>

    /**
     * Clone client's mono-repository to local storage.
     * Requires Git configuration to be set up first.
     *
     * @param clientId The client ID
     * @return ResponseEntity with clone result
     */
    @PostExchange("/clients/{clientId}/clone")
    suspend fun cloneRepository(
        @PathVariable clientId: String,
    ): ResponseEntity<CloneResultDto>

    /**
     * Inherit Git configuration from client to project.
     * Optionally allows project-specific configuration overrides.
     *
     * @param projectId The project ID
     * @param clientId The client ID to inherit from
     * @return ResponseEntity with success status
     */
    @PostExchange("/projects/{projectId}/inherit-git")
    suspend fun inheritGitConfig(
        @PathVariable projectId: String,
        @RequestParam clientId: String,
    ): ResponseEntity<Map<String, Any>>

    /**
     * Retrieves existing Git credentials for a client (masked).
     * Sensitive fields are not returned, only their presence is indicated.
     *
     * @param clientId The client ID
     * @return GitCredentialsDto with masked credentials, or null if not found
     */
    @GetExchange("/clients/{clientId}/credentials")
    suspend fun getGitCredentials(
        @PathVariable clientId: String,
    ): GitCredentialsDto?

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
