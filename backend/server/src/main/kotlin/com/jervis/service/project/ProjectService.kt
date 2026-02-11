package com.jervis.service.project

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.ProjectDto
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.entity.ProjectDocument
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.repository.ProjectRepository
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
    private val gitRepositoryService: GitRepositoryService,
    private val directoryStructureService: DirectoryStructureService,
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

        // Trigger async repo sync if project has REPOSITORY resources
        val hasRepos = savedProject.resources.any { it.capability == ConnectionCapability.REPOSITORY }
        if (hasRepos) {
            bgScope.launch {
                try {
                    gitRepositoryService.syncProjectRepositories(savedProject)
                } catch (e: Exception) {
                    logger.error(e) { "Background repo sync failed for project ${savedProject.name}" }
                }
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
     * Get or create the JERVIS internal project for a client.
     * Max 1 internal project per client. Auto-created on first orchestration.
     */
    suspend fun getOrCreateJervisProject(clientId: ClientId): ProjectDocument {
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
