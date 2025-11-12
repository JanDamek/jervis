package com.jervis.controller.api

import com.jervis.dto.ClientDto
import com.jervis.dto.CloneResultDto
import com.jervis.dto.GitCredentialsDto
import com.jervis.dto.GitBranchListDto
import com.jervis.dto.GitSetupRequestDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ProjectGitOverrideRequestDto
import com.jervis.mapper.toDto
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.IGitConfigurationService
import com.jervis.service.client.ClientService
import com.jervis.service.git.GitConfigurationService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * REST API controller for Git configuration operations.
 * Provides endpoints for setting up Git providers, validating access, and cloning repositories.
 * Handles both client-level and project-level Git configuration.
 */
@RestController
@RequestMapping("/api/v1/git")
class GitConfigurationRestController(
    private val gitConfigurationService: GitConfigurationService,
    private val clientService: ClientService,
    private val projectRepository: ProjectMongoRepository,
) : IGitConfigurationService {
    private val logger = KotlinLogging.logger {}

    @PostMapping("/clients/{clientId}/setup")
    override suspend fun setupGitConfiguration(
        @PathVariable clientId: String,
        @RequestBody request: GitSetupRequestDto,
    ): ClientDto {
        logger.info { "Setting up Git configuration for client: $clientId" }

        val result = gitConfigurationService.setupGitForClient(ObjectId(clientId), request)

        if (result.isFailure) {
            logger.error { "Git setup failed for client: $clientId - ${result.exceptionOrNull()?.message}" }
            throw IllegalStateException("Git configuration failed: ${result.exceptionOrNull()?.message}")
        }

        logger.info { "Git setup successful for client: $clientId" }

        val client =
            clientService.getClientById(ObjectId(clientId))
                ?: throw IllegalStateException("Client not found after Git setup: $clientId")

        return client.toDto()
    }

    @PostMapping("/clients/{clientId}/test-connection")
    override suspend fun testConnection(
        @PathVariable clientId: String,
        @RequestBody request: GitSetupRequestDto,
    ): Map<String, Any> {
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
            mapOf(
                "success" to true,
                "message" to "Repository access validated successfully",
            )
        } else {
            logger.warn { "Git connection test failed for client: $clientId" }
            mapOf(
                "success" to false,
                "message" to "Unable to access repository with provided credentials",
            )
        }
    }

    @PostMapping("/clients/{clientId}/clone")
    override suspend fun cloneRepository(
        @PathVariable clientId: String,
    ): CloneResultDto {
        logger.info { "Cloning repository for client: $clientId" }

        val result = gitConfigurationService.cloneClientRepository(ObjectId(clientId))

        return if (result.isSuccess) {
            val repoPath = result.getOrNull()
            logger.info { "Repository cloned successfully for client: $clientId at $repoPath" }
            CloneResultDto(
                success = true,
                repositoryPath = repoPath?.toString(),
                message = "Repository cloned successfully",
            )
        } else {
            logger.error { "Repository clone failed for client: $clientId - ${result.exceptionOrNull()?.message}" }
            CloneResultDto(
                success = false,
                repositoryPath = null,
                message = result.exceptionOrNull()?.message ?: "Failed to clone repository",
            )
        }
    }

    @PostMapping("/projects/{projectId}/inherit-git")
    override suspend fun inheritGitConfig(
        @PathVariable projectId: String,
        @RequestParam clientId: String,
    ): Map<String, Any> {
        logger.info { "Inheriting Git config from client $clientId to project $projectId" }

        val result =
            gitConfigurationService.inheritGitConfigToProject(
                ObjectId(clientId),
                ObjectId(projectId),
                null,
            )

        return if (result.isSuccess) {
            logger.info { "Git config inherited successfully for project: $projectId" }
            mapOf(
                "success" to true,
                "message" to "Git configuration inherited successfully",
            )
        } else {
            logger.error { "Git config inheritance failed for project: $projectId - ${result.exceptionOrNull()?.message}" }
            mapOf(
                "success" to false,
                "message" to (result.exceptionOrNull()?.message ?: "Failed to inherit Git configuration"),
            )
        }
    }

    @Deprecated("Git config moved to client level")
    @PostMapping("/projects/{projectId}/setup-override")
    override suspend fun setupGitOverrideForProject(
        @PathVariable projectId: String,
        @RequestBody request: ProjectGitOverrideRequestDto,
    ): ProjectDto {
        logger.info { "Setting up Git override for project: $projectId" }

        @Suppress("DEPRECATION")
        val result = gitConfigurationService.setupGitOverrideForProject(ObjectId(projectId), request)

        if (result.isFailure) {
            logger.error { "Git override setup failed for project: $projectId - ${result.exceptionOrNull()?.message}" }
            throw IllegalStateException("Git override configuration failed: ${result.exceptionOrNull()?.message}")
        }

        logger.info { "Git override setup successful for project: $projectId" }

        val project =
            projectRepository.findById(ObjectId(projectId))
                ?: throw IllegalStateException("Project not found after Git override setup: $projectId")

        return project.toDto()
    }

    @GetMapping("/clients/{clientId}/credentials")
    override suspend fun getGitCredentials(
        @PathVariable clientId: String,
    ): GitCredentialsDto? {
        logger.info { "Retrieving Git credentials for client: $clientId" }

        val credentials = gitConfigurationService.getGitCredentials(ObjectId(clientId))

        logger.info {
            "Git credentials retrieved for client: $clientId, " +
                "hasCredentials=${credentials != null}"
        }

        return credentials
    }

    @GetMapping("/clients/{clientId}/branches")
    override suspend fun listRemoteBranches(
        @PathVariable clientId: String,
        @RequestParam(required = false) repoUrl: String?,
    ): GitBranchListDto {
        logger.info { "Listing remote branches for client: $clientId" }
        return gitConfigurationService.listRemoteBranches(ObjectId(clientId), repoUrl)
    }

    @PostMapping("/clients/{clientId}/default-branch")
    override suspend fun setDefaultBranch(
        @PathVariable clientId: String,
        @RequestParam branch: String,
    ): ClientDto {
        logger.info { "Setting default branch for client: $clientId -> $branch" }
        val result = gitConfigurationService.updateDefaultBranch(ObjectId(clientId), branch)
        if (result.isFailure) {
            throw IllegalStateException("Failed to set default branch: ${result.exceptionOrNull()?.message}")
        }
        val client = clientService.getClientById(ObjectId(clientId))
            ?: throw IllegalStateException("Client not found after update: $clientId")
        return client.toDto()
    }
}
