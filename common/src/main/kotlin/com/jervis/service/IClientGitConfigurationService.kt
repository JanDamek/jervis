package com.jervis.service

import com.jervis.dto.ClientDto
import com.jervis.dto.GitCredentialsDto
import com.jervis.dto.GitSetupRequestDto
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

/**
 * HTTP Exchange interface for Git configuration operations.
 * Used by desktop client to configure Git settings including credentials.
 */
@HttpExchange("/api/v1/git")
interface IClientGitConfigurationService {
    /**
     * Sets up complete Git configuration for a client including credentials.
     * This saves SSH keys, HTTPS tokens, GPG keys to ServiceCredentialsDocument.
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
     * Retrieves existing Git credentials for a client (decrypted).
     * Returns null if no credentials are configured.
     *
     * @param clientId The client ID
     * @return GitCredentialsDto with decrypted credentials, or null if not found
     */
    @GetExchange("/clients/{clientId}/credentials")
    suspend fun getGitCredentials(
        @PathVariable clientId: String,
    ): GitCredentialsDto?
}
