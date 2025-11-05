package com.jervis.ui.component

import com.jervis.client.NotificationsWebSocketClient
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.IClientProjectLinkService
import com.jervis.service.IClientService
import com.jervis.service.IEmailAccountService
import com.jervis.service.IGitConfigurationService
import com.jervis.service.IProjectService
import com.jervis.service.ITaskSchedulingService
import com.jervis.service.debug.DesktopDebugWindowService
import com.jervis.ui.utils.MacOSAppUtils
import com.jervis.ui.window.ClientsWindow
import com.jervis.ui.window.MainWindow
import com.jervis.ui.window.SchedulerWindow
import com.jervis.ui.window.TrayIconManager
import com.jervis.ui.window.project.ProjectSettingWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.swing.UIManager

class ApplicationWindowManager(
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
    private val confluenceService: com.jervis.service.IConfluenceService,
    private val userTaskService: com.jervis.service.IUserTaskService,
    private val ragSearchService: com.jervis.service.IRagSearchService,
) {
    private val mainWindow: MainWindow by lazy {
        MainWindow(
            projectService,
            chatCoordinator,
            clientService,
            linkService,
            this,
            debugWindowService,
            notificationsClient,
        )
    }

    private val projectSettingsWindow: ProjectSettingWindow by lazy {
        ProjectSettingWindow(
            projectService,
            clientService,
            gitConfigurationService,
            jiraSetupService,
            integrationSettingsService,
            confluenceService,
            this,
        )
    }

    private val clientsWindow: ClientsWindow by lazy {
        ClientsWindow(
            clientService,
            gitConfigurationService,
            projectService,
            linkService,
            emailAccountService,
            jiraSetupService,
            integrationSettingsService,
            this,
        )
    }

    private val schedulerWindow: SchedulerWindow by lazy {
        SchedulerWindow(taskSchedulingService, clientService, projectService, chatCoordinator, notificationsClient)
    }

    private val trayIconManager: TrayIconManager by lazy {
        TrayIconManager(this)
    }

    // User Tasks window
    private val userTasksWindow: com.jervis.ui.window.UserTasksWindow by lazy {
        com.jervis.ui.window
            .UserTasksWindow(userTaskService, clientService, chatCoordinator, this)
    }

    // RAG Search window
    private val ragSearchWindow: com.jervis.ui.window.RagSearchWindow by lazy {
        com.jervis.ui.window
            .RagSearchWindow(ragSearchService, clientService, projectService)
    }

    @Volatile
    private var currentClientId: String? = null

    private val badgeScope = CoroutineScope(Dispatchers.IO)

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

    fun showRagSearchWindow() {
        ragSearchWindow.isVisible = true
        ragSearchWindow.toFront()
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
        mainWindow.reloadClientsAndProjects()
        mainWindow.isVisible = true
    }

    fun showProjectSettingWindow() {
        projectSettingsWindow.reloadProjects()
        projectSettingsWindow.isVisible = true
    }

    fun showProjectEditDialog(projectId: String) {
        projectSettingsWindow.reloadProjects()
        projectSettingsWindow.isVisible = true
        projectSettingsWindow.openEditDialogForProject(projectId)
    }

    fun showClientsWindow() {
        clientsWindow.reloadClientsAndProjects()
        clientsWindow.isVisible = true
    }

    fun showSchedulerWindow() {
        schedulerWindow.reloadClientsAndProjects()
        schedulerWindow.isVisible = true
    }

    fun showDebugWindow() {
        debugWindowService.showDebugWindow()
    }

    fun showUserTasksWindow() {
        userTasksWindow.isVisible = true
        userTasksWindow.refreshTasks()
    }

    fun getNotificationsSessionId(): String = notificationsClient.sessionId

    fun updateCurrentClientId(clientId: String) {
        currentClientId = clientId
        updateUserTaskBadgeForClient(clientId)
    }

    fun updateUserTaskBadgeForClient(clientId: String) {
        badgeScope.launch {
            try {
                // Always show total active user-tasks across all clients
                val clients = clientService.list()
                var total = 0
                for (c in clients) {
                    runCatching { userTaskService.activeCount(c.id).activeCount }.onSuccess { total += it }
                }
                MacOSAppUtils.setDockBadgeCount(total)
            } catch (_: Exception) {
                // ignore badge update errors
            }
        }
    }

    fun broadcastReloadClientsAndProjects() {
        mainWindow.reloadClientsAndProjects()
        clientsWindow.reloadClientsAndProjects()
        schedulerWindow.reloadClientsAndProjects()
        projectSettingsWindow.reloadProjects()
    }
}
