package com.jervis.ui.component

import com.jervis.service.admin.PromptManagementService
import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.service.client.ClientProjectLinkService
import com.jervis.service.client.ClientService
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.indexing.ClientIndexingService
import com.jervis.service.indexing.IndexingService
import com.jervis.service.project.ProjectService
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.prompts.PromptTemplateService
import com.jervis.service.scheduling.TaskQueryService
import com.jervis.service.scheduling.TaskSchedulingService
import com.jervis.ui.window.ClientsWindow
import com.jervis.ui.window.MainWindow
import com.jervis.ui.window.ProjectSettingWindow
import com.jervis.ui.window.PromptManagementWindow
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
    private val promptManagementService: PromptManagementService,
    private val promptRepository: PromptRepository,
    private val promptTemplateService: PromptTemplateService,
    private val llmGateway: LlmGateway,
) {
    private val mainWindow: MainWindow by lazy {
        MainWindow(
            projectService,
            chatCoordinator,
            clientService,
            linkService,
            taskContextService,
            promptManagementService,
            llmGateway,
            promptRepository,
            promptTemplateService,
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

    private val promptManagementWindow: PromptManagementWindow by lazy {
        PromptManagementWindow(promptManagementService, llmGateway, promptRepository, promptTemplateService, this)
    }

    private val trayIconManager: TrayIconManager by lazy {
        TrayIconManager(this)
    }

    fun initialize() {
        // Configure UI Manager properties to prevent text overlap issues
        configureUIRendering()
        trayIconManager
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

    fun showPromptManagement() {
        promptManagementWindow.isVisible = true
    }
}
