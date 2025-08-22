package com.jervis

import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.service.client.ClientProjectLinkService
import com.jervis.service.client.ClientService
import com.jervis.service.project.ProjectService
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
@ComponentScan(
    basePackages = [
        "com.jervis.service", "com.jervis.ui.component",
        "com.jervis.repository", "com.jervis.configuration", "com.jervis.controller",
    ],
)
class JervisApplication(
    private val projectService: ProjectService,
    private val chatCoordinator: AgentOrchestratorService,
    private val clientService: ClientService,
    private val linkService: ClientProjectLinkService,
) {
    private val logger = KotlinLogging.logger {}

    @Bean
    fun initApp(): ApplicationRunner =
        ApplicationRunner {
            // Ensure a default project is set (fallback to first if none)
            ensureDefaultProject()

            // Create a window manager
            val applicationWindows =
                ApplicationWindowManager(
                    projectService,
                    chatCoordinator,
                    clientService,
                    linkService,
                )

            EventQueue.invokeLater {
                // Initialize the application and show the main chat window
                applicationWindows.initialize()
                applicationWindows.showMainWindow()
            }
            setDockIcon()
        }

    /**
     * Ensures that a default project exists for the application.
     */
    private fun ensureDefaultProject() {
        // If a default project already exists, nothing to do
        if (projectService.getDefaultProjectBlocking() != null) {
            return
        }

        // Try to use the first available project as default
        projectService.getAllProjectsBlocking().firstOrNull()?.let { anyProject ->
            projectService.setDefaultProjectBlocking(anyProject)
            logger.info { "Default project automatically set to first available: ${anyProject.name}" }
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
