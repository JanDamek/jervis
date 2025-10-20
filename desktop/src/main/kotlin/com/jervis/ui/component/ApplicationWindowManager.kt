package com.jervis.ui.component

import com.jervis.client.NotificationsWebSocketClient
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.IClientGitConfigurationService
import com.jervis.service.IClientIndexingService
import com.jervis.service.IClientProjectLinkService
import com.jervis.service.IClientService
import com.jervis.service.IIndexingMonitorService
import com.jervis.service.IIndexingService
import com.jervis.service.IProjectGitConfigurationService
import com.jervis.service.IProjectService
import com.jervis.service.ITaskContextService
import com.jervis.service.ITaskSchedulingService
import com.jervis.service.debug.DesktopDebugWindowService
import com.jervis.ui.utils.MacOSAppUtils
import com.jervis.ui.window.ClientsWindow
import com.jervis.ui.window.MainWindow
import com.jervis.ui.window.ProjectSettingWindow
import com.jervis.ui.window.SchedulerWindow
import com.jervis.ui.window.TrayIconManager
import javax.swing.UIManager

class ApplicationWindowManager(
    private val projectService: IProjectService,
    private val chatCoordinator: IAgentOrchestratorService,
    private val clientService: IClientService,
    private val clientGitConfigurationService: IClientGitConfigurationService,
    private val projectGitConfigurationService: IProjectGitConfigurationService,
    private val linkService: IClientProjectLinkService,
    private val taskContextService: ITaskContextService,
    private val indexingService: IIndexingService,
    private val clientIndexingService: IClientIndexingService,
    private val taskSchedulingService: ITaskSchedulingService,
    private val indexingMonitorService: IIndexingMonitorService,
    private val debugWindowService: DesktopDebugWindowService,
    private val notificationsClient: NotificationsWebSocketClient,
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
            debugWindowService,
            notificationsClient,
        )
    }

    private val projectSettingsWindow: ProjectSettingWindow by lazy {
        ProjectSettingWindow(
            projectService,
            clientService,
            indexingService,
            clientIndexingService,
            projectGitConfigurationService,
        )
    }

    private val clientsWindow: ClientsWindow by lazy {
        ClientsWindow(clientService, clientGitConfigurationService, projectService, linkService, indexingService)
    }

    private val schedulerWindow: SchedulerWindow by lazy {
        SchedulerWindow(taskSchedulingService, clientService, projectService, chatCoordinator, notificationsClient)
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

    fun showDebugWindow() {
        debugWindowService.showDebugWindow()
    }
}
