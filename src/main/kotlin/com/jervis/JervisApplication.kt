package com.jervis

import com.jervis.module.llmcoordinator.LlmCoordinator
import com.jervis.service.ChatService
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
@EntityScan(basePackages = ["com.jervis.entity", "com.jervis.module.memory"])
@EnableJpaRepositories(basePackages = ["com.jervis.repository", "com.jervis.module.memory"])
class JervisApplication(
    private val settingService: SettingService,
    private val projectService: ProjectService,
    private val chatService: ChatService,
    private val llmCoordinator: LlmCoordinator,
) {
    private val logger = KotlinLogging.logger {}

    @Bean
    fun initApp(): ApplicationRunner =
        ApplicationRunner {
            // Check if an active project exists and set it if needed
            ensureActiveProject()

            // Load startup minimization settings
            val startMinimized = settingService.getBooleanValue("startup_minimize", false)

            // Create window manager
            val applicationWindows = ApplicationWindowManager(settingService, projectService, chatService, llmCoordinator)

            EventQueue.invokeLater {
                // Initialize application with minimization settings
                applicationWindows.initialize(startMinimized)
            }
            setDockIcon()
        }

    /**
     * Ensures that an active project exists for the RAG service
     */
    private fun ensureActiveProject() {
        // Skip if we already have an active project
        if (projectService.getActiveProject() != null) {
            return
        }

        // Try to use the default project
        projectService.getDefaultProject()?.let { defaultProject ->
            projectService.setActiveProject(defaultProject)
            logger.info { "Default project automatically set: ${defaultProject.name}" }
            return
        }

        // Try to use the first available project
        projectService.getAllProjects().firstOrNull()?.let { anyProject ->
            projectService.setDefaultProject(anyProject)
            projectService.setActiveProject(anyProject)
            logger.info { "First available project automatically set: ${anyProject.name}" }
            return
        }

        // No projects available
        logger.warn { "Warning: No projects available. RAG service will not be fully functional." }
    }
}

fun main(args: Array<String>) {
    // Explicitly disable headless mode
    System.setProperty("java.awt.headless", "false")

    // Set the dock icon path (only works at startup, not during runtime)
    System.setProperty("apple.awt.application.name", "JERVIS Assistant")

    runApplication<JervisApplication>(*args)
}
