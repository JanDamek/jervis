package com.jervis.service.project

import com.jervis.entity.mongo.ProjectDocument
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

    override suspend fun getAllProjects(): List<ProjectDocument> = projectRepository.findAll().toList()

    override suspend fun getDefaultProject(): ProjectDocument? = projectRepository.findByIsActiveIsTrue()

    override suspend fun setActiveProject(project: ProjectDocument) {
        setDefaultProject(project)
    }

    override suspend fun setDefaultProject(project: ProjectDocument) {
        // First, remove the default status from all projects
        val allProjects = getAllProjects()
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

    override suspend fun saveProject(
        project: ProjectDocument,
        makeDefault: Boolean,
    ): ProjectDocument {
        val isNew = false
        val updatedProject = project.copy(updatedAt = Instant.now())
        val savedProject = projectRepository.save(updatedProject)

        if (makeDefault) {
            setDefaultProject(savedProject)
        }

        if (isNew) {
            logger.info { "Created new project: ${savedProject.name}" }
        } else {
            logger.info { "Updated project: ${savedProject.name}" }
        }

        return savedProject
    }

    override suspend fun deleteProject(project: ProjectDocument) {
        if (project.isActive) {
            logger.warn { "Attempting to delete default project: ${project.name}" }
        }

        projectRepository.delete(project)
        logger.info { "Deleted project: ${project.name}" }
    }

    suspend fun getAllProjectsBlocking(): List<ProjectDocument> = getAllProjects()

    suspend fun getDefaultProjectBlocking(): ProjectDocument? = getDefaultProject()

    suspend fun setDefaultProjectBlocking(project: ProjectDocument) = setDefaultProject(project)

    override suspend fun getProjectByName(name: String?): ProjectDocument =
        requireNotNull(name?.let { projectRepository.findByName(it) }) {
            "Project not found with name: $name"
        }
}
