package com.jervis.service

import com.jervis.dto.ClientDto
import com.jervis.dto.CloneResultDto
import com.jervis.dto.GitCredentialsDto
import com.jervis.dto.GitBranchListDto
import com.jervis.dto.GitSetupRequestDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ProjectGitOverrideRequestDto
import com.jervis.dto.GitTestConnectionResponseDto
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.Path
import de.jensklingenberg.ktorfit.http.Query

/**
 * HTTP Exchange interface for Git configuration operations.
 * Used by desktop client to configure Git settings for clients and projects.
 * Handles both client-level and project-level Git configuration including credentials.
 */
  interface IGitConfigurationService {
    /**
     * Sets up complete Git configuration for a client including credentials.
     * Credentials are encrypted and stored directly in ClientDocument.
     *
     * @param clientId The client ID
     * @param request Complete Git setup including provider, auth type, credentials, and config
     * @return Updated ClientDto with Git configuration
     */
    @POST("api/v1/git/clients/{clientId}/setup")
    suspend fun setupGitConfiguration(
        @Path clientId: String,
        @Body request: GitSetupRequestDto,
    ): ClientDto

    /**
     * Test Git repository connection with provided credentials.
     * Validates that the repository is accessible before saving configuration.
     *
     * @param clientId The client ID
     * @param request Git setup request with credentials to test
     * @return Map with success status and message
     */
    @POST("api/v1/git/clients/{clientId}/test-connection")
    suspend fun testConnection(
        @Path clientId: String,
        @Body request: GitSetupRequestDto,
    ): GitTestConnectionResponseDto

    /**
     * Clone client's mono-repository to local storage.
     * Requires Git configuration to be set up first.
     *
     * @param clientId The client ID
     * @return Clone result
     */
    @POST("api/v1/git/clients/{clientId}/clone")
    suspend fun cloneRepository(
        @Path clientId: String,
    ): CloneResultDto

    /**
     * Inherit Git configuration from client to project.
     * Optionally allows project-specific configuration overrides.
     *
     * @param projectId The project ID
     * @param clientId The client ID to inherit from
     * @return Map with success status
     */
    @POST("api/v1/git/projects/{projectId}/inherit-git")
    suspend fun inheritGitConfig(
        @Path projectId: String,
        @Query clientId: String,
    ): Map<String, Any>

    /**
     * Retrieves existing Git credentials for a client (masked).
     * Sensitive fields are not returned, only their presence is indicated.
     *
     * @param clientId The client ID
     * @return GitCredentialsDto with masked credentials, or null if not found
     */
    @GET("api/v1/git/clients/{clientId}/credentials")
    suspend fun getGitCredentials(
        @Path clientId: String,
    ): GitCredentialsDto?

    /**
     * List remote branches for a client's configured repository (or provided repoUrl override).
     * Returns discovered default branch (if resolvable) and list of remote branch names.
     */
    @GET("api/v1/git/clients/{clientId}/branches")
    suspend fun listRemoteBranches(
        @Path clientId: String,
        @Query("repoUrl") repoUrl: String? = null,
    ): GitBranchListDto

    /**
     * Update client's default branch to the selected remote branch.
     */
    @POST("api/v1/git/clients/{clientId}/default-branch")
    suspend fun setDefaultBranch(
        @Path clientId: String,
        @Query("branch") branch: String,
    ): ClientDto

    /**
     * Setup Git override configuration for a project.
     * Allows projects to use different credentials, remote URL, and auth type than the client.
     *
     * @param projectId The project ID
     * @param request The Git override configuration including credentials
     * @return Updated ProjectDto with new Git overrides
     */
    @POST("api/v1/git/projects/{projectId}/setup-override")
    suspend fun setupGitOverrideForProject(
        @Path projectId: String,
        @Body request: ProjectGitOverrideRequestDto,
    ): ProjectDto
}
