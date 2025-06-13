package com.jervis.service

import com.jervis.entity.Project
import com.jervis.module.indexer.IndexerService
import com.jervis.repository.ProjectRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class ProjectService(
    private val projectRepository: ProjectRepository,
    private val settingService: SettingService,
    private val indexerService: IndexerService,
) {
    companion object {
        // Klíč pro uložení ID aktivního projektu v nastavení
        const val ACTIVE_PROJECT_ID = "active_project_id"
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Získá všechny projekty
     */
    fun getAllProjects(): List<Project> = projectRepository.findAll()

    /**
     * Získá projekt podle ID
     */
    fun getProjectById(id: Long): Project? = projectRepository.findById(id).orElse(null)

    /**
     * Získá aktivní projekt
     */
    fun getActiveProject(): Project? {
        val projectId = settingService.getIntValue(ACTIVE_PROJECT_ID, -1)
        return if (projectId > 0) {
            getProjectById(projectId.toLong())
        } else {
            // Pokud není nastaven aktivní projekt, zkusíme najít výchozí
            getDefaultProject()
        }
    }

    /**
     * Získá výchozí projekt, pokud existuje
     */
    fun getDefaultProject(): Project? = projectRepository.findByActiveIsTrue()

    /**
     * Nastaví projekt jako aktivní
     */
    @Transactional
    fun setActiveProject(project: Project) {
        // Uložíme ID projektu do nastavení
        settingService.saveIntSetting(ACTIVE_PROJECT_ID, project.id?.toInt() ?: -1)

        // Oznámíme změnu aktivního projektu RAG službě
        // TODO call rag service for set active project
        //        ragService.notifyProjectChanged(project)
    }

    /**
     * Nastaví projekt jako výchozí a zruší výchozí stav u ostatních projektů
     */
    @Transactional
    fun setDefaultProject(project: Project) {
        // Nejprve zrušíme výchozí stav u všech projektů
        val allProjects = getAllProjects()
        allProjects.forEach {
            if (it.active && it.id != project.id) {
                it.active = false
                it.updatedAt = LocalDateTime.now()
                projectRepository.save(it)
            }
        }

        // Nastavíme výchozí stav u vybraného projektu
        if (!project.active) {
            project.active = true
            project.updatedAt = LocalDateTime.now()
            projectRepository.save(project)
        }

        // Pokud není nastaven aktivní projekt, nastavíme tento projekt jako aktivní
        if (settingService.getIntValue(ACTIVE_PROJECT_ID, -1) <= 0) {
            setActiveProject(project)
        }
    }

    /**
     * Vytvoří nebo aktualizuje projekt
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
            // Pokud je to první projekt nebo je explicitně požadováno, nastavíme ho jako výchozí
            setDefaultProject(savedProject)
        }

        return savedProject
    }

    /**
     * Smaže projekt
     */
    @Transactional
    fun deleteProject(project: Project) {
        val isDefault = project.active
        val isActive = getActiveProject()?.id == project.id

        projectRepository.delete(project)

        if (isActive) {
            // Pokud byl projekt aktivní, nastavíme jako aktivní výchozí projekt
            val defaultProject = getDefaultProject()
            if (defaultProject != null) {
                setActiveProject(defaultProject)
            } else {
                // Pokud není žádný výchozí projekt, vymažeme nastavení aktivního projektu
                settingService.saveIntSetting(ACTIVE_PROJECT_ID, -1)
            }
        }

        if (isDefault) {
            // Pokud byl projekt výchozí, nastavíme jako výchozí první dostupný projekt
            val firstProject = getAllProjects().firstOrNull()
            if (firstProject != null) {
                setDefaultProject(firstProject)
            }
        }
    }

    /**
     * Načte zdrojové kódy projektu do RAG
     * Provede indexaci, embedování a uložení do QDrant vector store
     */
    fun uploadProjectSource(project: Project) {
        if (project.path.isNullOrBlank()) {
            logger.warn { "Projekt ${project.name} nemá nastavenou cestu ke zdrojovým kódům" }
            return
        }

        logger.info { "Načítání zdrojových kódů projektu ${project.name} do RAG" }

        try {
            // Použijeme IndexerService pro indexaci celého adresáře projektu
            indexerService.indexProject(project)
            logger.info { "Zdrojové kódy projektu ${project.name} byly úspěšně načteny do RAG" }
        } catch (e: Exception) {
            logger.error(e) { "Chyba při načítání zdrojových kódů projektu ${project.name}: ${e.message}" }
        }
    }
}
