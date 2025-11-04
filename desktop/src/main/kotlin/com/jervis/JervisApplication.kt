package com.jervis

import com.formdev.flatlaf.FlatLightLaf
import com.jervis.client.NotificationsWebSocketClient
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.IClientProjectLinkService
import com.jervis.service.IClientService
import com.jervis.service.IEmailAccountService
import com.jervis.service.IGitConfigurationService
import com.jervis.service.IProjectService
import com.jervis.service.ITaskSchedulingService
import com.jervis.service.debug.DesktopDebugWindowService
import com.jervis.ui.component.ApplicationWindowManager
import com.jervis.ui.utils.MacOSAppUtils.configureMacOSSettings
import com.jervis.ui.utils.MacOSAppUtils.setDockIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import java.awt.EventQueue
import javax.swing.UIManager

@ComponentScan(
    basePackages = [
        "com.jervis.service.debug",
        "com.jervis.ui.component",
        "com.jervis.configuration",
    ],
)
@SpringBootApplication
class JervisApplication(
    private val projectService: IProjectService,
    private val chatCoordinator: IAgentOrchestratorService,
    private val clientService: IClientService,
    private val gitConfigurationService: IGitConfigurationService,
    private val linkService: IClientProjectLinkService,
    private val taskSchedulingService: ITaskSchedulingService,
    private val debugWindowService: DesktopDebugWindowService,
    private val notificationsClient: NotificationsWebSocketClient,
    private val emailAccountService: IEmailAccountService,
    private val jiraSetupService: com.jervis.service.IJiraSetupService,
    private val integrationSettingsService: com.jervis.service.IIntegrationSettingsService,
    private val userTaskService: com.jervis.service.IUserTaskService,
) {
    private val logger = KotlinLogging.logger {}

    @Bean
    fun initApp(): ApplicationRunner =
        ApplicationRunner {
            CoroutineScope(Dispatchers.Default).launch {
                ensureDefaultProject()
            }

            val applicationWindows =
                ApplicationWindowManager(
                    projectService,
                    chatCoordinator,
                    clientService,
                    gitConfigurationService,
                    linkService,
                    taskSchedulingService,
                    debugWindowService,
                    notificationsClient,
                    emailAccountService,
                    jiraSetupService,
                    integrationSettingsService,
                    userTaskService,
                )

            EventQueue.invokeLater {
                applicationWindows.initialize()
                applicationWindows.showMainWindow()
                setDockIcon()
            }
        }

    /**
     * Ensures that a default project exists for the application.
     */
    private suspend fun ensureDefaultProject() {
        if (projectService.getDefaultProject() != null) {
            return
        }

        projectService.getAllProjects().firstOrNull()?.let { anyProject ->
            projectService.setDefaultProject(anyProject)
            logger.info { "Default project automatically set to first available: ${anyProject.name}" }
            return
        }

        logger.warn { "Warning: No projects available. RAG service will not be fully functional." }
    }
}

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "false")
    System.setProperty("apple.awt.application.name", "JERVIS Assistant")

    // Configure macOS-specific settings before UI initialization
    configureMacOSSettings()

    try {
        UIManager.setLookAndFeel(FlatLightLaf())
    } catch (_: Exception) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (_: Exception) {
            // If system L&F also fails, use default cross-platform L&F
            println("Warning: Could not set Look and Feel - using default")
        }
    }

    runApplication<JervisApplication>(*args)
}
