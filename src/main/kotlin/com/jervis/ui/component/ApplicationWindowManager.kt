package com.jervis.ui.component

import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.service.client.ClientProjectLinkService
import com.jervis.service.client.ClientService
import com.jervis.service.indexing.IndexingService
import com.jervis.service.project.ProjectService
import com.jervis.ui.window.ClientsWindow
import com.jervis.ui.window.MainWindow
import com.jervis.ui.window.ProjectSettingWindow
import com.jervis.ui.window.TrayIconManager
import javax.swing.UIManager

/**
 * Třída pro centrální správu všech oken aplikace
 */
class ApplicationWindowManager(
    private val projectService: ProjectService,
    private val chatCoordinator: AgentOrchestratorService,
    private val clientService: ClientService,
    private val linkService: ClientProjectLinkService,
    private val taskContextService: TaskContextService,
    private val indexingService: IndexingService,
) {
    private val mainWindow: MainWindow by lazy {
        MainWindow(projectService, chatCoordinator, clientService, linkService, taskContextService)
    }

    private val projectSettingsWindow: ProjectSettingWindow by lazy {
        ProjectSettingWindow(projectService, clientService, indexingService)
    }

    private val clientsWindow: ClientsWindow by lazy {
        ClientsWindow(clientService, projectService, linkService, indexingService)
    }

    private val trayIconManager: TrayIconManager by lazy {
        TrayIconManager(this)
    }

    /**
     * Inicializuje aplikační okna a systémovou ikonu
     */
    fun initialize() {
        // Configure UI Manager properties to prevent text overlap issues
        configureUIRendering()
        trayIconManager
    }

    /**
     * Configure system-wide UI rendering to prevent text overlap in all Swing components
     */
    private fun configureUIRendering() {
        // Ensure all list and table cell renderers are opaque by default
        UIManager.put("List.cellRenderer.opaque", true)
        UIManager.put("Table.cellRenderer.opaque", true)
        UIManager.put("TableHeader.cellRenderer.opaque", true)

        // Configure file chooser components to be opaque
        UIManager.put("FileChooser.listViewWindowsStyle", false)
        UIManager.put("FileChooser.usesSingleFilePane", true)

        // Ensure text rendering is proper across all components
        UIManager.put("swing.aatext", true)
        UIManager.put("awt.useSystemAAFontSettings", "on")

        // Force component opacity for better text rendering
        UIManager.put("Panel.opaque", true)
        UIManager.put("ScrollPane.opaque", true)
        UIManager.put("Viewport.opaque", true)
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
