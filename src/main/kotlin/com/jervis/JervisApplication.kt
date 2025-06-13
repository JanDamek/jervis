package com.jervis

import com.jervis.service.ProjectService
import com.jervis.service.SettingService
import com.jervis.utils.MacOSAppUtils.setDockIcon
import com.jervis.window.ApplicationWindowManager
import mu.KotlinLogging
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import java.awt.EventQueue

@SpringBootApplication
@EntityScan("com.jervis.entity")
@EnableJpaRepositories("com.jervis.repository")
class JervisApplication(
    private val settingService: SettingService,
    private val projectService: ProjectService,
    private val chatService: com.jervis.service.ChatService,
) {
    private val logger = KotlinLogging.logger {}
    @Bean
    fun initApp(): ApplicationRunner =
        ApplicationRunner {
            // Zkontrolujeme, zda existuje aktivní projekt a případně ho nastavíme
            ensureActiveProject()

            // Načteme nastavení pro minimalizaci při startu
            val startMinimized = settingService.getBooleanValue("startup_minimize", false)

            // Vytvoření správce oken
            val applicationWindows = ApplicationWindowManager(settingService, projectService, chatService)

            EventQueue.invokeLater {
                // Inicializace aplikace s nastavením minimalizace
                applicationWindows.initialize(startMinimized)
            }
            setDockIcon()
        }

    /**
     * Zajistí, že existuje aktivní projekt pro RAG službu
     */
    private fun ensureActiveProject() {
        // Zkontrolujeme, zda máme aktivní projekt
        if (projectService.getActiveProject() == null) {
            // Pokud nemáme aktivní projekt, zkusíme použít výchozí
            val defaultProject = projectService.getDefaultProject()
            if (defaultProject != null) {
                projectService.setActiveProject(defaultProject)
                logger.info { "Automaticky nastaven výchozí projekt: ${defaultProject.name}" }
            } else {
                // Pokud nemáme ani výchozí projekt, zkusíme použít první dostupný
                val anyProject = projectService.getAllProjects().firstOrNull()
                if (anyProject != null) {
                    // Nastavíme tento projekt jako výchozí a aktivní
                    projectService.setDefaultProject(anyProject)
                    projectService.setActiveProject(anyProject)
                    logger.info { "Automaticky nastaven první dostupný projekt: ${anyProject.name}" }
                } else {
                    logger.warn { "Upozornění: Není dostupný žádný projekt. RAG služba nebude plně funkční." }
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    // Explicitně vypnout headless režim
    System.setProperty("java.awt.headless", "false")

    // Nastavit cestu k ikoně pro dock (funguje pouze při spuštění, ne za běhu)
    System.setProperty("apple.awt.application.name", "JERVIS Assistant")

    runApplication<JervisApplication>(*args)
}
