package com.jervis.service.project

import com.jervis.dto.ProjectDto
import com.jervis.entity.ProjectDocument
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.repository.ProjectRepository
import com.jervis.service.storage.DirectoryStructureService
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

    suspend fun saveProject(project: ProjectDocument): ProjectDto {
        val existing = projectRepository.findById(project.id)
        val isNew = existing == null

        val savedProject = projectRepository.save(project)

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
        requireNotNull(name?.let { projectRepository.findByName(it) }) {
            "Project not found with name: $name"
        }
}
