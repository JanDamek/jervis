package com.jervis.ui.component

import com.jervis.service.controller.ChatService
import com.jervis.service.llm.LlmCoordinator
import com.jervis.service.llm.lmstudio.LMStudioService
import com.jervis.service.llm.ollama.OllamaService
import com.jervis.service.project.ProjectService
import com.jervis.service.setting.SettingService
import com.jervis.ui.window.MainWindow
import com.jervis.ui.window.ProjectSettingWindow
import com.jervis.ui.window.SettingsWindow
import com.jervis.ui.window.TrayIconManager
import com.jervis.ui.window.ClientsWindow
import com.jervis.service.client.ClientProjectLinkService

/**
 * Třída pro centrální správu všech oken aplikace
 */
class ApplicationWindowManager(
    private val settingService: SettingService,
    private val projectService: ProjectService,
    private val chatService: ChatService,
    private val llmCoordinator: LlmCoordinator,
    private val ollamaService: OllamaService,
    private val lmStudioService: LMStudioService,
    private val clientService: com.jervis.service.client.ClientService,
    private val linkService: com.jervis.service.client.ClientProjectLinkService,
) {
    private val mainWindow: MainWindow by lazy {
        MainWindow(projectService, chatService)
    }

    private val settingsWindow: SettingsWindow by lazy {
        SettingsWindow(settingService, llmCoordinator, ollamaService, lmStudioService)
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
    fun initialize(startMinimized: Boolean) {
        // Inicializace ikony v systémové liště
        trayIconManager

        // Zobrazení hlavního okna, pokud není nastaveno minimalizované spuštění
        if (!startMinimized) {
            showMainWindow()
        }
    }

    /**
     * Zobrazí hlavní okno aplikace
     */
    fun showMainWindow() {
        mainWindow.isVisible = true
    }

    /**
     * Zobrazí okno nastavení
     */
    fun showSettingsWindow() {
        settingsWindow.isVisible = true
    }

    /**
     * Skryje všechna okna aplikace
     */
    fun hideAllWindows() {
        mainWindow.isVisible = false
        settingsWindow.isVisible = false
        projectSettingsWindow.isVisible = false
        clientsWindow.isVisible = false
    }

    /**
     * Uvolní všechny prostředky
     */
    fun dispose() {
        mainWindow.dispose()
        settingsWindow.dispose()
        projectSettingsWindow.dispose()
        clientsWindow.dispose()
        trayIconManager.dispose()
    }

    fun showProjectSettingWindow() {
        projectSettingsWindow.isVisible = true
    }

    fun showClientsWindow() {
        clientsWindow.isVisible = true
    }
}
