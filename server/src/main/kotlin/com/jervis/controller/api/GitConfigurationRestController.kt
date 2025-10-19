package com.jervis.controller.api

import com.jervis.dto.CloneResultDto
import com.jervis.dto.GitSetupRequestDto
import com.jervis.service.git.GitConfigurationService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * REST API controller for Git configuration operations.
 * Provides endpoints for setting up Git providers, validating access, and cloning repositories.
 */
@RestController
@RequestMapping("/api/v1/git")
class GitConfigurationRestController(
    private val gitConfigurationService: GitConfigurationService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Setup Git configuration for a client.
     * Includes provider selection, authentication credentials, and workflow rules.
     */
    @PostMapping("/clients/{clientId}/setup")
    suspend fun setupGitForClient(
        @PathVariable clientId: String,
        @RequestBody request: GitSetupRequestDto,
    ): ResponseEntity<Map<String, Any>> {
        logger.info { "Setting up Git configuration for client: $clientId" }

        val result = gitConfigurationService.setupGitForClient(ObjectId(clientId), request)

        return if (result.isSuccess) {
            logger.info { "Git setup successful for client: $clientId" }
            ResponseEntity.ok(
                mapOf(
                    "success" to true,
                    "message" to "Git configuration saved successfully",
                ),
            )
        } else {
            logger.error { "Git setup failed for client: $clientId - ${result.exceptionOrNull()?.message}" }
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                mapOf(
                    "success" to false,
                    "message" to (result.exceptionOrNull()?.message ?: "Unknown error"),
                ),
            )
        }
    }

    /**
     * Test Git repository connection with provided credentials.
     * Validates that the repository is accessible before saving configuration.
     */
    @PostMapping("/clients/{clientId}/test-connection")
    suspend fun testConnection(
        @PathVariable clientId: String,
        @RequestBody request: GitSetupRequestDto,
    ): ResponseEntity<Map<String, Any>> {
        logger.info { "Testing Git connection for client: $clientId" }

        val result =
            gitConfigurationService.validateGitAccess(
                ObjectId(clientId),
                request.monoRepoUrl,
                request.gitAuthType,
                request,
            )

        return if (result.isSuccess && result.getOrNull() == true) {
            logger.info { "Git connection test successful for client: $clientId" }
            ResponseEntity.ok(
                mapOf(
                    "success" to true,
                    "message" to "Repository access validated successfully",
                ),
            )
        } else {
            logger.warn { "Git connection test failed for client: $clientId" }
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                mapOf(
                    "success" to false,
                    "message" to "Unable to access repository with provided credentials",
                ),
            )
        }
    }

    /**
     * Clone client's mono-repository to local storage.
     * Requires Git configuration to be set up first.
     */
    @PostMapping("/clients/{clientId}/clone")
    suspend fun cloneRepository(
        @PathVariable clientId: String,
    ): ResponseEntity<CloneResultDto> {
        logger.info { "Cloning repository for client: $clientId" }

        val result = gitConfigurationService.cloneClientRepository(ObjectId(clientId))

        return if (result.isSuccess) {
            val repoPath = result.getOrNull()
            logger.info { "Repository cloned successfully for client: $clientId at $repoPath" }
            ResponseEntity.ok(
                CloneResultDto(
                    success = true,
                    repositoryPath = repoPath?.toString(),
                    message = "Repository cloned successfully",
                ),
            )
        } else {
            logger.error { "Repository clone failed for client: $clientId - ${result.exceptionOrNull()?.message}" }
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                CloneResultDto(
                    success = false,
                    repositoryPath = null,
                    message = result.exceptionOrNull()?.message ?: "Failed to clone repository",
                ),
            )
        }
    }

    /**
     * Inherit Git configuration from client to project.
     * Optionally allows project-specific configuration overrides.
     */
    @PostMapping("/projects/{projectId}/inherit-git")
    suspend fun inheritGitConfig(
        @PathVariable projectId: String,
        @RequestParam clientId: String,
    ): ResponseEntity<Map<String, Any>> {
        logger.info { "Inheriting Git config from client $clientId to project $projectId" }

        val result =
            gitConfigurationService.inheritGitConfigToProject(
                ObjectId(clientId),
                ObjectId(projectId),
                null,
            )

        return if (result.isSuccess) {
            logger.info { "Git config inherited successfully for project: $projectId" }
            ResponseEntity.ok(
                mapOf(
                    "success" to true,
                    "message" to "Git configuration inherited successfully",
                ),
            )
        } else {
            logger.error { "Git config inheritance failed for project: $projectId - ${result.exceptionOrNull()?.message}" }
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                mapOf(
                    "success" to false,
                    "message" to (result.exceptionOrNull()?.message ?: "Failed to inherit Git configuration"),
                ),
            )
        }
    }
}
