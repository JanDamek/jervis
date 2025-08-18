package com.jervis.service.project

import com.jervis.entity.mongo.ProjectDocument
import com.jervis.entity.mongo.SettingType
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.indexer.IndexerService
import com.jervis.service.indexer.ProjectIndexer
import com.jervis.service.setting.SettingService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ProjectService(
    private val projectRepository: ProjectMongoRepository,
    private val settingService: SettingService,
    private val indexerService: IndexerService,
    private val projectIndexer: ProjectIndexer,
) {
    companion object {
        // Key for storing active project ID in settings
        const val ACTIVE_PROJECT_ID = "active_project_id"
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Gets all projects
     */
    suspend fun getAllProjects(): List<ProjectDocument> = projectRepository.findAll().toList()

    /**
     * Gets a project by ID
     */
    suspend fun getProjectById(id: ObjectId): ProjectDocument? = projectRepository.findById(id.toString())

    /**
     * Gets the active project
     */
    suspend fun getActiveProject(): ProjectDocument? {
        val projectIdString = settingService.getEffectiveValue(ACTIVE_PROJECT_ID)
        return if (!projectIdString.isNullOrBlank()) {
            try {
                val projectId = ObjectId(projectIdString)
                getProjectById(projectId)
            } catch (e: IllegalArgumentException) {
                logger.warn("Invalid ObjectId format for active project: $projectIdString")
                null
            }
        } else {
            // If no active project is set, try to find the default one
            getDefaultProject()
        }
    }

    /**
     * Gets the default project, if it exists
     */
    suspend fun getDefaultProject(): ProjectDocument? = projectRepository.findByIsActiveIsTrue()

    /**
     * Sets a project as active
     */
    suspend fun setActiveProject(project: ProjectDocument) {
        // Save project ID to settings
        settingService.saveValue(ACTIVE_PROJECT_ID, project.id.toString(), SettingType.STRING)

        // Notify RAG service about active project change
        // TODO call rag service for set active project
        //        ragService.notifyProjectChanged(project)
    }

    /**
     * Sets a project as default and removes default status from other projects
     */
    suspend fun setDefaultProject(project: ProjectDocument) {
        // First, remove default status from all projects
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

        // Set default status for the selected project
        if (!project.isActive) {
            val updatedProject =
                project.copy(
                    isActive = true,
                    updatedAt = Instant.now(),
                )
            projectRepository.save(updatedProject)
        }

        // If no active project is set, set this project as active
        val activeProjectId = settingService.getEffectiveValue(ACTIVE_PROJECT_ID)
        if (activeProjectId.isNullOrBlank()) {
            setActiveProject(project)
        }
    }

    /**
     * Creates or updates a project
     */
    suspend fun saveProject(
        project: ProjectDocument,
        makeDefault: Boolean = false,
    ): ProjectDocument {
        val isNew = project.id == null
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

        // Check if this is the active project
        val activeProjectId = settingService.getEffectiveValue(ACTIVE_PROJECT_ID)
        if (activeProjectId == project.id?.toString()) {
            // Clear active project setting
            settingService.saveValue(ACTIVE_PROJECT_ID, "", SettingType.STRING)
            logger.info("Cleared active project setting due to project deletion")
        }

        projectRepository.delete(project)
        logger.info("Deleted project: ${project.name}")
    }

    /**
     * Uploads project source code for indexing
     */
    suspend fun uploadProjectSource(project: ProjectDocument) {
        try {
            logger.info("Starting source upload for project: ${project.name}")

            runBlocking {
                logger.info("Indexing project source: ${project.name}")
            }

            runBlocking {
                logger.info("Processing project files: ${project.name}")
            }

            runBlocking {
                indexerService.indexProject(project)
                logger.debug("Project indexing completed for ${project.name}")
            }

            runBlocking {
                logger.info("Finalizing project indexing: ${project.name}")
            }

            runBlocking {
                logger.info("Project source upload completed: ${project.name}")
            }
        } catch (e: Exception) {
            logger.error("Error uploading project source for ${project.name}: ${e.message}", e)
            throw e
        }
    }

    // Blocking wrapper methods for compatibility
    fun getAllProjectsBlocking(): List<ProjectDocument> =
        runBlocking {
            getAllProjects()
        }

    fun getProjectByIdBlocking(id: String): ProjectDocument? =
        runBlocking {
            try {
                val objectId = ObjectId(id)
                getProjectById(objectId)
            } catch (e: IllegalArgumentException) {
                logger.warn("Invalid ObjectId format: $id")
                null
            }
        }

    fun getActiveProjectBlocking(): ProjectDocument? =
        runBlocking {
            getActiveProject()
        }

    fun getDefaultProjectBlocking(): ProjectDocument? =
        runBlocking {
            getDefaultProject()
        }

    fun setActiveProjectBlocking(project: ProjectDocument) =
        runBlocking {
            setActiveProject(project)
        }

    fun setDefaultProjectBlocking(project: ProjectDocument) =
        runBlocking {
            setDefaultProject(project)
        }

    fun saveProjectBlocking(
        project: ProjectDocument,
        makeDefault: Boolean = false,
    ): ProjectDocument =
        runBlocking {
            saveProject(project, makeDefault)
        }

    fun deleteProjectBlocking(project: ProjectDocument) =
        runBlocking {
            deleteProject(project)
        }

    fun uploadProjectSourceBlocking(project: ProjectDocument) =
        runBlocking {
            uploadProjectSource(project)
        }
}
