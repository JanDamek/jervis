package com.jervis.service.project

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.ProjectDto
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.entity.ProjectDocument
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.entity.SystemConfigDocument
import com.jervis.repository.ProjectRepository
import com.jervis.repository.SystemConfigRepository
import com.jervis.service.indexing.git.GitRepositoryService
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class ProjectService(
    private val projectRepository: ProjectRepository,
    private val systemConfigRepository: SystemConfigRepository,
    private val gitRepositoryService: GitRepositoryService,
    private val directoryStructureService: DirectoryStructureService,
    private val applicationEventPublisher: org.springframework.context.ApplicationEventPublisher,
) {
    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun getAllProjects(): List<ProjectDocument> = projectRepository.findAll().toList()

    suspend fun listProjectsForClient(clientId: ClientId): List<ProjectDocument> = projectRepository.findByClientId(clientId).toList()

    suspend fun saveProject(project: ProjectDocument): ProjectDto {
        val existing = getProjectByIdOrNull(project.id)
        val isNew = existing == null

        val merged =
            existing?.copy(
                name = project.name,
                description = project.description,
                groupId = project.groupId,
                communicationLanguageEnum = project.communicationLanguageEnum,
                buildConfig = project.buildConfig,
                cloudModelPolicy = project.cloudModelPolicy,
                gitCommitConfig = project.gitCommitConfig,
                connectionCapabilities = project.connectionCapabilities,
                resources = project.resources,
                resourceLinks = project.resourceLinks,
            )
                ?: project

        val savedProject = projectRepository.save(merged)

        directoryStructureService.ensureProjectDirectories(savedProject.clientId, savedProject.id)

        if (isNew) {
            logger.info { "Created new project: ${savedProject.name}" }
        } else {
            logger.info { "Updated project: ${savedProject.name}" }
        }

        // Trigger async repo sync if project has REPOSITORY resources (for indexing)
        val hasRepos = savedProject.resources.any { it.capability == ConnectionCapability.REPOSITORY }
        if (hasRepos) {
            bgScope.launch {
                try {
                    gitRepositoryService.syncProjectRepositories(savedProject)
                } catch (e: Exception) {
                    logger.error(e) { "Background repo sync failed for project ${savedProject.name}" }
                }
            }

            // Trigger workspace initialization for agent — always unless already CLONING
            // READY → refresh (git fetch), null → clone, CLONE_FAILED_* → retry, NOT_NEEDED → re-check
            if (savedProject.workspaceStatus != com.jervis.entity.WorkspaceStatus.CLONING) {
                // Reset retry state for failed clones so init starts fresh (no stale backoff)
                val resetProject = if (savedProject.workspaceStatus?.isCloneFailed == true) {
                    projectRepository.save(
                        savedProject.copy(
                            workspaceStatus = null,
                            workspaceRetryCount = 0,
                            nextWorkspaceRetryAt = null,
                            lastWorkspaceError = null,
                        ),
                    )
                } else {
                    savedProject
                }
                logger.info { "Publishing workspace init event for project ${resetProject.name} (current status: ${savedProject.workspaceStatus})" }
                applicationEventPublisher.publishEvent(ProjectWorkspaceInitEvent(resetProject))
            } else {
                logger.info { "Project ${savedProject.name} workspace is CLONING — skipping re-trigger" }
            }
        }

        return savedProject.toDto()
    }

    suspend fun deleteProject(project: ProjectDto) {
        val projectDoc = project.toDocument()
        projectRepository.delete(projectDoc)
        logger.info { "Deleted project: ${projectDoc.name}" }
    }

    suspend fun getProjectByName(name: String?): ProjectDocument =
        requireNotNull(name?.let { n -> projectRepository.findAll().toList().find { it.name == n } }) {
            "Project not found with name: $name"
        }

    suspend fun getProjectById(projectId: ProjectId): ProjectDocument =
        requireNotNull(getProjectByIdOrNull(projectId)) {
            "Project not found with id: $projectId"
        }

    suspend fun getProjectByIdOrNull(projectId: ProjectId): ProjectDocument? =
        projectRepository.getById(projectId)

    /**
     * Get the JERVIS internal project. Prefers SystemConfig setting, falls back to per-client auto-create.
     */
    suspend fun getOrCreateJervisProject(clientId: ClientId): ProjectDocument {
        // 1. Try SystemConfig (explicit selection from Settings UI)
        val config = systemConfigRepository.findById(SystemConfigDocument.SINGLETON_ID)
        if (config?.jervisInternalProjectId != null) {
            val configured = projectRepository.getById(ProjectId(config.jervisInternalProjectId))
            if (configured != null) return configured
        }

        // 2. Fallback: find or create per-client internal project
        val existing = projectRepository.findByClientIdAndIsJervisInternal(clientId, true)
        if (existing != null) return existing

        val project = ProjectDocument(
            clientId = clientId,
            name = "JERVIS Internal",
            description = "Interní projekt pro plánování a orchestraci",
            isJervisInternal = true,
        )
        val saved = projectRepository.save(project)
        directoryStructureService.ensureProjectDirectories(saved.clientId, saved.id)
        logger.info { "Auto-created JERVIS internal project for client $clientId" }
        return saved
    }
}
