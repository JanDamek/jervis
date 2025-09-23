package com.jervis.ui.component

import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.service.client.ClientProjectLinkService
import com.jervis.service.client.ClientService
import com.jervis.service.indexing.ClientIndexingService
import com.jervis.service.indexing.IndexingService
import com.jervis.service.indexing.monitoring.IndexingMonitorService
import com.jervis.service.project.ProjectService
import com.jervis.service.scheduling.TaskQueryService
import com.jervis.service.scheduling.TaskSchedulingService
import com.jervis.ui.utils.MacOSAppUtils
import com.jervis.ui.window.ClientsWindow
import com.jervis.ui.window.MainWindow
import com.jervis.ui.window.ProjectSettingWindow
import com.jervis.ui.window.SchedulerWindow
import com.jervis.ui.window.TrayIconManager
import javax.swing.UIManager

class ApplicationWindowManager(
    private val projectService: ProjectService,
    private val chatCoordinator: AgentOrchestratorService,
    private val clientService: ClientService,
    private val linkService: ClientProjectLinkService,
    private val taskContextService: TaskContextService,
    private val indexingService: IndexingService,
    private val clientIndexingService: ClientIndexingService,
    private val taskSchedulingService: TaskSchedulingService,
    private val taskQueryService: TaskQueryService,
    private val indexingMonitorService: IndexingMonitorService,
) {
    private val mainWindow: MainWindow by lazy {
        MainWindow(
            projectService,
            chatCoordinator,
            clientService,
            linkService,
            taskContextService,
            indexingMonitorService,
            this,
        )
    }

    private val projectSettingsWindow: ProjectSettingWindow by lazy {
        ProjectSettingWindow(projectService, clientService, indexingService, clientIndexingService)
    }

    private val clientsWindow: ClientsWindow by lazy {
        ClientsWindow(clientService, projectService, linkService, indexingService)
    }

    private val schedulerWindow: SchedulerWindow by lazy {
        SchedulerWindow(taskSchedulingService, taskQueryService, clientService, projectService, chatCoordinator)
    }

    private val trayIconManager: TrayIconManager by lazy {
        TrayIconManager(this)
    }

    fun initialize() {
        // Configure macOS-specific settings first
        MacOSAppUtils.configureMacOSSettings()

        // Configure UI Manager properties to prevent text overlap issues
        configureUIRendering()

        // Setup macOS native menu bar and dock context menu
        setupMacOSMenus()

        // Initialize tray icon (only if not on macOS or as fallback)
        try {
            trayIconManager
        } catch (e: IllegalStateException) {
            println("Warning: Failed to initialize system tray icon: ${e.message}")
            // Application continues to work without tray icon
        }
    }

    /**
     * Sets up macOS-specific menu functionality
     */
    private fun setupMacOSMenus() {
        // Setup native macOS menu bar and apply it to the main window
        val nativeMenuBar = MacOSAppUtils.setupNativeMenuBar(this)
        if (nativeMenuBar != null) {
            mainWindow.jMenuBar = nativeMenuBar
        }

        // Setup dock context menu
        MacOSAppUtils.setupDockContextMenu(this)
    }

    /**
     * Configure system-wide UI rendering to prevent text overlap in all Swing components
     */
    private fun configureUIRendering() {
        UIManager.put("List.cellRenderer.opaque", true)
        UIManager.put("Table.cellRenderer.opaque", true)
        UIManager.put("TableHeader.cellRenderer.opaque", true)

        UIManager.put("FileChooser.listViewWindowsStyle", false)
        UIManager.put("FileChooser.usesSingleFilePane", true)

        UIManager.put("swing.aatext", true)
        UIManager.put("awt.useSystemAAFontSettings", "on")

        UIManager.put("Panel.opaque", true)
        UIManager.put("ScrollPane.opaque", true)
        UIManager.put("Viewport.opaque", true)
    }

    fun showMainWindow() {
        mainWindow.isVisible = true
    }

    fun showProjectSettingWindow() {
        projectSettingsWindow.isVisible = true
    }

    fun showClientsWindow() {
        clientsWindow.isVisible = true
    }

    fun showSchedulerWindow() {
        schedulerWindow.isVisible = true
    }

    fun showIndexingMonitor() {
        mainWindow.showIndexingMonitor()
    }
}
