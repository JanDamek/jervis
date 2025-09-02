package com.jervis

import com.formdev.flatlaf.FlatLightLaf
import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.service.client.ClientProjectLinkService
import com.jervis.service.client.ClientService
import com.jervis.service.indexing.IndexingService
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
import javax.swing.UIManager

@SpringBootApplication
@EnableMongoRepositories(basePackages = ["com.jervis.repository.mongo"])
@ComponentScan(
    basePackages = [
        "com.jervis.service", "com.jervis.ui.component",
        "com.jervis.repository", "com.jervis.configuration", "com.jervis.controller",
        "com.jervis.util",
    ],
)
class JervisApplication(
    private val projectService: ProjectService,
    private val chatCoordinator: AgentOrchestratorService,
    private val clientService: ClientService,
    private val linkService: ClientProjectLinkService,
    private val taskContextService: TaskContextService,
    private val indexingService: IndexingService,
) {
    private val logger = KotlinLogging.logger {}

    @Bean
    fun initApp(): ApplicationRunner =
        ApplicationRunner {
            ensureDefaultProject()

            val applicationWindows =
                ApplicationWindowManager(
                    projectService,
                    chatCoordinator,
                    clientService,
                    linkService,
                    taskContextService,
                    indexingService,
                )

            EventQueue.invokeLater {
                applicationWindows.initialize()
                applicationWindows.showMainWindow()
            }
            setDockIcon()
        }

    /**
     * Ensures that a default project exists for the application.
     */
    private fun ensureDefaultProject() {
        if (projectService.getDefaultProjectBlocking() != null) {
            return
        }

        projectService.getAllProjectsBlocking().firstOrNull()?.let { anyProject ->
            projectService.setDefaultProjectBlocking(anyProject)
            logger.info { "Default project automatically set to first available: ${anyProject.name}" }
            return
        }

        logger.warn { "Warning: No projects available. RAG service will not be fully functional." }
    }
}

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "false")
    System.setProperty("apple.awt.application.name", "JERVIS Assistant")

    try {
        UIManager.setLookAndFeel(FlatLightLaf())
    } catch (e: Exception) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (ex: Exception) {
            // If system L&F also fails, use default cross-platform L&F
            println("Warning: Could not set Look and Feel - using default")
        }
    }

    runApplication<JervisApplication>(*args)
}
