package com.jervis.ui.component

import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.service.client.ClientProjectLinkService
import com.jervis.service.client.ClientService
import com.jervis.service.project.ProjectService
import com.jervis.ui.window.ClientsWindow
import com.jervis.ui.window.MainWindow
import com.jervis.ui.window.ProjectSettingWindow
import com.jervis.ui.window.TrayIconManager

/**
 * Třída pro centrální správu všech oken aplikace
 */
class ApplicationWindowManager(
    private val projectService: ProjectService,
    private val chatCoordinator: AgentOrchestratorService,
    private val clientService: ClientService,
    private val linkService: ClientProjectLinkService,
) {
    private val mainWindow: MainWindow by lazy {
        MainWindow(projectService, chatCoordinator, clientService, linkService)
    }

    private val projectSettingsWindow: ProjectSettingWindow by lazy {
        ProjectSettingWindow(projectService, clientService)
    }

    private val clientsWindow: ClientsWindow by lazy {
        ClientsWindow(clientService, projectService, linkService)
    }

    private val trayIconManager: TrayIconManager by lazy {
        TrayIconManager(this)
    }

    /**
     * Inicializuje aplikační okna a systémovou ikonu
     */
    fun initialize() {
        trayIconManager
    }

    /**
     * Zobrazí hlavní okno aplikace
     */
    fun showMainWindow() {
        mainWindow.isVisible = true
    }

    fun showProjectSettingWindow() {
        projectSettingsWindow.isVisible = true
    }

    fun showClientsWindow() {
        clientsWindow.isVisible = true
    }
}
