package com.jervis.service.project

import com.jervis.dto.ProjectDto
import com.jervis.entity.ProjectDocument
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.repository.ProjectMongoRepository
import com.jervis.service.git.GitRepositoryService
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ProjectService(
    private val projectRepository: ProjectMongoRepository,
    private val gitRepositoryService: GitRepositoryService,
    private val directoryStructureService: DirectoryStructureService,
    private val configCache: com.jervis.service.cache.ClientProjectConfigCache,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun getAllProjects(): List<ProjectDocument> = projectRepository.findAll().toList()

    suspend fun getDefaultProject(): ProjectDocument? = projectRepository.findByIsActiveIsTrue()

    suspend fun setActiveProject(project: ProjectDocument) {
        setDefaultProject(project)
    }

    suspend fun setDefaultProject(project: ProjectDocument) {
        val allProjects = projectRepository.findAll().toList()
        allProjects.forEach { existingProject ->
            if (existingProject.isActive && existingProject.id != project.id) {
                val updatedProject =
                    existingProject.copy(
                        isActive = false,
                        updatedAt = Instant.now(),
                    )
                projectRepository.save(updatedProject)
            }
        }

        // Set the default status for the selected project
        if (!project.isActive) {
            val updatedProject =
                project.copy(
                    isActive = true,
                    updatedAt = Instant.now(),
                )
            projectRepository.save(updatedProject)
        }
    }

    suspend fun saveProject(
        project: ProjectDocument,
        makeDefault: Boolean,
    ): ProjectDto {
        val existing = projectRepository.findById(project.id)
        val isNew = existing == null

        val savedProject =
            if (isNew) {
                val newProject = project.copy(createdAt = Instant.now(), updatedAt = Instant.now())
                projectRepository.save(newProject)
            } else {
                val updatedProject =
                    project.copy(
                        createdAt = existing.createdAt,
                        updatedAt = Instant.now(),
                    )
                projectRepository.save(updatedProject)
            }

        if (makeDefault) {
            setDefaultProject(savedProject)
        }

        directoryStructureService.ensureProjectDirectories(savedProject.clientId, savedProject.id)

        // Invalidate cache for this client so changes propagate immediately
        configCache.invalidateClient(savedProject.clientId)

        if (isNew) {
            logger.info { "Created new project: ${savedProject.name}, cache invalidated" }
        } else {
            logger.info { "Updated project: ${savedProject.name}, cache invalidated" }
        }

        return savedProject.toDto()
    }

    suspend fun deleteProject(project: ProjectDto) {
        val projectDoc = project.toDocument()
        if (projectDoc.isActive) {
            logger.warn { "Attempting to delete default project: ${projectDoc.name}" }
        }

        projectRepository.delete(projectDoc)

        // Invalidate cache for this client so deletion propagates immediately
        configCache.invalidateClient(projectDoc.clientId)

        logger.info { "Deleted project: ${projectDoc.name}, cache invalidated" }
    }

    suspend fun getProjectByName(name: String?): ProjectDocument =
        requireNotNull(name?.let { projectRepository.findByName(it) }) {
            "Project not found with name: $name"
        }
}
