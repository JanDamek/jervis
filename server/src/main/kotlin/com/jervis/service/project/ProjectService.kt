package com.jervis.service.project

import com.jervis.dto.ProjectDto
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.IProjectService
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ProjectService(
    private val projectRepository: ProjectMongoRepository,
) : IProjectService {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override suspend fun getAllProjects(): List<ProjectDto> = projectRepository.findAll().toList().map { it.toDto() }

    override suspend fun getDefaultProject(): ProjectDto? = projectRepository.findByIsActiveIsTrue()?.toDto()

    override suspend fun setActiveProject(project: ProjectDto) {
        setDefaultProject(project)
    }

    override suspend fun setDefaultProject(project: ProjectDto) {
        val projectDoc = project.toDocument()

        // First, remove the default status from all projects
        val allProjects = projectRepository.findAll().toList()
        allProjects.forEach { existingProject ->
            if (existingProject.isActive && existingProject.id != projectDoc.id) {
                val updatedProject =
                    existingProject.copy(
                        isActive = false,
                        updatedAt = Instant.now(),
                    )
                projectRepository.save(updatedProject)
            }
        }

        // Set the default status for the selected project
        if (!projectDoc.isActive) {
            val updatedProject =
                projectDoc.copy(
                    isActive = true,
                    updatedAt = Instant.now(),
                )
            projectRepository.save(updatedProject)
        }
    }

    override suspend fun saveProject(
        project: ProjectDto,
        makeDefault: Boolean,
    ): ProjectDto {
        val projectDoc = project.toDocument()
        val isNew = false
        val updatedProject = projectDoc.copy(updatedAt = Instant.now())
        val savedProject = projectRepository.save(updatedProject)

        if (makeDefault) {
            setDefaultProject(savedProject.toDto())
        }

        if (isNew) {
            logger.info { "Created new project: ${savedProject.name}" }
        } else {
            logger.info { "Updated project: ${savedProject.name}" }
        }

        return savedProject.toDto()
    }

    override suspend fun deleteProject(project: ProjectDto) {
        val projectDoc = project.toDocument()
        if (projectDoc.isActive) {
            logger.warn { "Attempting to delete default project: ${projectDoc.name}" }
        }

        projectRepository.delete(projectDoc)
        logger.info { "Deleted project: ${projectDoc.name}" }
    }

    suspend fun getAllProjectsBlocking(): List<ProjectDocument> = projectRepository.findAll().toList()

    suspend fun getDefaultProjectBlocking(): ProjectDocument? = projectRepository.findByIsActiveIsTrue()

    suspend fun setDefaultProjectBlocking(project: ProjectDocument) {
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

        if (!project.isActive) {
            val updatedProject =
                project.copy(
                    isActive = true,
                    updatedAt = Instant.now(),
                )
            projectRepository.save(updatedProject)
        }
    }

    override suspend fun getProjectByName(name: String?): ProjectDto =
        requireNotNull(name?.let { projectRepository.findByName(it) }) {
            "Project not found with name: $name"
        }.toDto()
}
