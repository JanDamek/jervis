package com.jervis.service.project

import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.mongo.ProjectMongoRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ProjectService(
    private val projectRepository: ProjectMongoRepository,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Gets all projects
     */
    suspend fun getAllProjects(): List<ProjectDocument> = projectRepository.findAll().toList()

    /**
     * Gets the default project if it exists
     */
    suspend fun getDefaultProject(): ProjectDocument? = projectRepository.findByIsActiveIsTrue()

    /**
     * Sets a project as active
     */
    suspend fun setActiveProject(project: ProjectDocument) {
        // Active project is aligned with a default project
        setDefaultProject(project)
    }

    /**
     * Sets a project as default and removes default status from other projects
     */
    suspend fun setDefaultProject(project: ProjectDocument) {
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

    /**
     * Creates or updates a project
     */
    suspend fun saveProject(
        project: ProjectDocument,
        makeDefault: Boolean = false,
    ): ProjectDocument {
        val isNew = false
        val updatedProject = project.copy(updatedAt = Instant.now())
        val savedProject = projectRepository.save(updatedProject)

        if (makeDefault) {
            setDefaultProject(savedProject)
        }

        if (isNew) {
            logger.info("Created new project: ${savedProject.name}")
        } else {
            logger.info("Updated project: ${savedProject.name}")
        }

        return savedProject
    }

    /**
     * Deletes a project
     */
    suspend fun deleteProject(project: ProjectDocument) {
        // Check if this is the default project
        if (project.isActive) {
            logger.warn("Attempting to delete default project: ${project.name}")
            // You might want to prevent deletion or set another project as default
        }

        projectRepository.delete(project)
        logger.info("Deleted project: ${project.name}")
    }

    // Blocking wrapper methods for compatibility
    fun getAllProjectsBlocking(): List<ProjectDocument> =
        runBlocking {
            getAllProjects()
        }

    fun getDefaultProjectBlocking(): ProjectDocument? =
        runBlocking {
            getDefaultProject()
        }

    fun setDefaultProjectBlocking(project: ProjectDocument) =
        runBlocking {
            setDefaultProject(project)
        }
}
