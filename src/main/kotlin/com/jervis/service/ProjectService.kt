package com.jervis.service

import com.jervis.entity.Project
import com.jervis.entity.SettingType
import com.jervis.module.indexer.IndexerService
import com.jervis.module.indexer.ProjectIndexer
import com.jervis.repository.ProjectRepository
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class ProjectService(
    private val projectRepository: ProjectRepository,
    private val settingService: SettingService,
    private val indexerService: IndexerService,
    private val projectIndexer: ProjectIndexer
) {
    companion object {
        // Key for storing active project ID in settings
        const val ACTIVE_PROJECT_ID = "active_project_id"
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Gets all projects
     */
    fun getAllProjects(): List<Project> = projectRepository.findAll()

    /**
     * Gets a project by ID
     */
    fun getProjectById(id: Long): Project? = projectRepository.findById(id).orElse(null)

    /**
     * Gets the active project
     */
    fun getActiveProject(): Project? {
        val projectIdStr = settingService.getEffectiveValue(ACTIVE_PROJECT_ID) ?: "-1"
        val projectId = projectIdStr.toIntOrNull() ?: -1
        return if (projectId > 0) {
            getProjectById(projectId.toLong())
        } else {
            // If no active project is set, try to find the default one
            getDefaultProject()
        }
    }

    /**
     * Gets the default project, if it exists
     */
    fun getDefaultProject(): Project? = projectRepository.findByActiveIsTrue()

    /**
     * Sets a project as active
     */
    @Transactional
    fun setActiveProject(project: Project) {
        // Save project ID to settings
        settingService.saveValue(ACTIVE_PROJECT_ID, (project.id?.toInt() ?: -1).toString(), SettingType.INTEGER)

        // Notify RAG service about active project change
        // TODO call rag service for set active project
        //        ragService.notifyProjectChanged(project)
    }

    /**
     * Sets a project as default and removes default status from other projects
     */
    @Transactional
    fun setDefaultProject(project: Project) {
        // First, remove default status from all projects
        val allProjects = getAllProjects()
        allProjects.forEach {
            if (it.active && it.id != project.id) {
                it.active = false
                it.updatedAt = LocalDateTime.now()
                projectRepository.save(it)
            }
        }

        // Set default status for the selected project
        if (!project.active) {
            project.active = true
            project.updatedAt = LocalDateTime.now()
            projectRepository.save(project)
        }

        // If no active project is set, set this project as active
        val activeProjectIdStr = settingService.getEffectiveValue(ACTIVE_PROJECT_ID) ?: "-1"
        val activeProjectId = activeProjectIdStr.toIntOrNull() ?: -1
        if (activeProjectId <= 0) {
            setActiveProject(project)
        }
    }

    /**
     * Creates or updates a project
     */
    @Transactional
    fun saveProject(
        project: Project,
        makeDefault: Boolean = false,
    ): Project {
        val isNew = project.id == null
        project.updatedAt = LocalDateTime.now()
        val savedProject = projectRepository.save(project)

        if (makeDefault || (isNew && getAllProjects().size == 1)) {
            // If it's the first project or explicitly requested, set it as default
            setDefaultProject(savedProject)
        }

        return savedProject
    }

    /**
     * Deletes a project
     */
    @Transactional
    fun deleteProject(project: Project) {
        val isDefault = project.active
        val isActive = getActiveProject()?.id == project.id

        projectRepository.delete(project)

        if (isActive) {
            // If the project was active, set the default project as active
            val defaultProject = getDefaultProject()
            if (defaultProject != null) {
                setActiveProject(defaultProject)
            } else {
                // If there's no default project, clear the active project setting
                settingService.saveValue(ACTIVE_PROJECT_ID, "-1", SettingType.INTEGER)
            }
        }

        if (isDefault) {
            // If the project was default, set the first available project as default
            val firstProject = getAllProjects().firstOrNull()
            if (firstProject != null) {
                setDefaultProject(firstProject)
            }
        }
    }

    /**
     * Loads project source code into RAG
     * Performs comprehensive indexing, including:
     * - Git integration
     * - Code chunking and embedding
     * - Dependency analysis
     * - TODO extraction
     * - Workspace management
     */
    suspend fun uploadProjectSource(project: Project) {
        if (project.path.isNullOrBlank()) {
            logger.warn { "Project ${project.name} doesn't have a path to source code set" }
            return
        }

        logger.info { "Loading source code of project ${project.name} into RAG" }

        try {
            // Use ProjectIndexer for comprehensive indexing
            val result = projectIndexer.indexProject(project)

            if (result.success) {
                logger.info { 
                    "Source code of project ${project.name} was successfully loaded into RAG. " +
                    "Files: ${result.filesProcessed}, " +
                    "Classes: ${result.classesProcessed}, " +
                    "Embeddings: ${result.embeddingsStored}, " +
                    "TODOs: ${result.todosExtracted}, " +
                    "Dependencies: ${result.dependenciesAnalyzed}"
                }
            } else {
                logger.error { "Error loading source code of project ${project.name}: ${result.errorMessage}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error loading source code of project ${project.name}: ${e.message}" }
        }
    }

    /**
     * Non-suspend version of getAllProjects that uses runBlocking
     */
    fun getAllProjectsBlocking(): List<Project> =
        runBlocking {
            getAllProjects()
        }

    /**
     * Non-suspend version of getProjectById that uses runBlocking
     */
    fun getProjectByIdBlocking(id: Long): Project? =
        runBlocking {
            getProjectById(id)
        }

    /**
     * Non-suspend version of getActiveProject that uses runBlocking
     */
    fun getActiveProjectBlocking(): Project? =
        runBlocking {
            getActiveProject()
        }

    /**
     * Non-suspend version of getDefaultProject that uses runBlocking
     */
    fun getDefaultProjectBlocking(): Project? =
        runBlocking {
            getDefaultProject()
        }

    /**
     * Non-suspend version of setActiveProject that uses runBlocking
     */
    fun setActiveProjectBlocking(project: Project) =
        runBlocking {
            setActiveProject(project)
        }

    /**
     * Non-suspend version of setDefaultProject that uses runBlocking
     */
    fun setDefaultProjectBlocking(project: Project) =
        runBlocking {
            setDefaultProject(project)
        }

    /**
     * Non-suspend version of saveProject that uses runBlocking
     */
    fun saveProjectBlocking(
        project: Project,
        makeDefault: Boolean = false,
    ): Project =
        runBlocking {
            saveProject(project, makeDefault)
        }

    /**
     * Non-suspend version of deleteProject that uses runBlocking
     */
    fun deleteProjectBlocking(project: Project) =
        runBlocking {
            deleteProject(project)
        }

    /**
     * Non-suspend version of uploadProjectSource that uses runBlocking
     */
    fun uploadProjectSourceBlocking(project: Project) =
        runBlocking {
            uploadProjectSource(project)
        }
}
