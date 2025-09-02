package com.jervis.ui.window.trayui

import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.service.client.ClientProjectLinkService
import com.jervis.service.client.ClientService
import com.jervis.service.indexing.IndexingService
import com.jervis.service.project.ProjectService
import com.jervis.ui.component.ApplicationWindowManager
import org.springframework.stereotype.Component

/**
 * Manager for tray UI windows.
 * This class manages the windows that can be opened from the system tray.
 */
@Component
class TrayWindowManager(
    projectService: ProjectService,
    chatCoordinator: AgentOrchestratorService,
    clientService: ClientService,
    linkService: ClientProjectLinkService,
    taskContextService: TaskContextService,
    indexingService: IndexingService,
) {
    private val windowManager =
        ApplicationWindowManager(
            projectService,
            chatCoordinator,
            clientService,
            linkService,
            taskContextService,
            indexingService,
        )

    /**
     * Initialize the window manager
     *
     * @param startMinimized Whether to start with windows minimized
     */
    fun initialize() {
        windowManager.initialize()
    }

    /**
     * Show the main window
     */
    fun showMainWindow() {
        windowManager.showMainWindow()
    }

    /**
     * Show the project settings window
     */
    fun showProjectSettingWindow() {
        windowManager.showProjectSettingWindow()
    }

    /**
     * Dispose of all windows
     */
    fun dispose() {
        // In a real implementation, this would dispose of all windows
        println("Disposing of all windows")
    }
}
