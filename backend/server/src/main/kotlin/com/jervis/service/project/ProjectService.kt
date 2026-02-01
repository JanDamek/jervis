package com.jervis.service.project

import com.jervis.dto.ProjectDto
import com.jervis.entity.ProjectDocument
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.repository.ProjectRepository
import com.jervis.service.storage.DirectoryStructureService
import com.jervis.types.ProjectId
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class ProjectService(
    private val projectRepository: ProjectRepository,
    // private val gitRepositoryService: GitRepositoryService, // Temporarily disabled
    private val directoryStructureService: DirectoryStructureService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun getAllProjects(): List<ProjectDocument> = projectRepository.findAll().toList()

    suspend fun listProjectsForClient(clientId: com.jervis.types.ClientId): List<ProjectDocument> = 
        projectRepository.findByClientId(clientId).toList()

    suspend fun saveProject(project: ProjectDocument): ProjectDto {
        val existing = getProjectByIdOrNull(project.id)
        val isNew = existing == null

        val merged = if (!isNew) {
            existing!!.copy(
                name = project.name,
                description = project.description,
                communicationLanguageEnum = project.communicationLanguageEnum,
                gitRepositoryConnectionId = project.gitRepositoryConnectionId,
                gitRepositoryIdentifier = project.gitRepositoryIdentifier,
                jiraProjectConnectionId = project.jiraProjectConnectionId,
                jiraProjectKey = project.jiraProjectKey,
                confluenceSpaceConnectionId = project.confluenceSpaceConnectionId,
                confluenceSpaceKey = project.confluenceSpaceKey,
                buildConfig = project.buildConfig,
                costPolicy = project.costPolicy,
                gitCommitConfig = project.gitCommitConfig
            )
        } else {
            project
        }

        val savedProject = projectRepository.save(merged)

        directoryStructureService.ensureProjectDirectories(savedProject.clientId, savedProject.id)

        if (isNew) {
            logger.info { "Created new project: ${savedProject.name}" }
        } else {
            logger.info { "Updated project: ${savedProject.name}" }
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
        projectRepository.findAll().toList().find { it.id == projectId }
}
