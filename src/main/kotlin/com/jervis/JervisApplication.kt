package com.jervis

import com.jervis.service.controller.ChatService
import com.jervis.service.llm.LlmCoordinator
import com.jervis.service.llm.lmstudio.LMStudioService
import com.jervis.service.llm.ollama.OllamaService
import com.jervis.service.project.ProjectService
import com.jervis.service.client.ClientService
import com.jervis.service.client.ClientProjectLinkService
import com.jervis.service.setting.SettingService
import com.jervis.ui.component.ApplicationWindowManager
import com.jervis.ui.utils.MacOSAppUtils.setDockIcon
import mu.KotlinLogging
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories
import java.awt.EventQueue

@SpringBootApplication
@EnableMongoRepositories(basePackages = ["com.jervis.repository.mongo"])
@ComponentScan(basePackages = ["com.jervis.service", "com.jervis.ui.component", "com.jervis.repository"])
class JervisApplication(
    private val settingService: SettingService,
    private val projectService: ProjectService,
    private val chatService: ChatService,
    private val llmCoordinator: LlmCoordinator,
    private val ollamaService: OllamaService,
    private val lmStudioService: LMStudioService,
    private val clientService: ClientService,
    private val linkService: ClientProjectLinkService,
) {
    private val logger = KotlinLogging.logger {}

    @Bean
    fun initApp(): ApplicationRunner =
        ApplicationRunner {
            // Check if an active project exists and set it if needed
            ensureActiveProject()

            // Load startup minimization settings
            val startMinimized = settingService.startupMinimize

            // Create a window manager
            val applicationWindows =
                ApplicationWindowManager(
                    settingService,
                    projectService,
                    chatService,
                    llmCoordinator,
                    ollamaService,
                    lmStudioService,
                    clientService,
                    linkService,
                )

            EventQueue.invokeLater {
                // Initialize the application with minimization settings
                applicationWindows.initialize(startMinimized)
            }
            setDockIcon()
        }

    /**
     * Ensures that an active project exists for the RAG service
     */
    private fun ensureActiveProject() {
        // Skip if we already have an active project
        if (projectService.getActiveProjectBlocking() != null) {
            return
        }

        // Try to use the default project
        projectService.getDefaultProjectBlocking()?.let { defaultProject ->
            projectService.setActiveProjectBlocking(defaultProject)
            logger.info { "Default project automatically set: ${defaultProject.name}" }
            return
        }

        // Try to use the first available project
        projectService.getAllProjectsBlocking().firstOrNull()?.let { anyProject ->
            projectService.setDefaultProjectBlocking(anyProject)
            projectService.setActiveProjectBlocking(anyProject)
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
