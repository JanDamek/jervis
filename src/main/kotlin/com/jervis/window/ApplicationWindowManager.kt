package com.jervis.window

import com.jervis.module.llmcoordinator.LlmCoordinator
import com.jervis.service.LMStudioService
import com.jervis.service.OllamaService
import com.jervis.service.ProjectService
import com.jervis.service.SettingService

/**
 * Třída pro centrální správu všech oken aplikace
 */
class ApplicationWindowManager(
    private val settingService: SettingService,
    private val projectService: ProjectService,
    private val chatService: com.jervis.service.ChatService,
    private val llmCoordinator: LlmCoordinator,
    private val ollamaService: OllamaService,
    private val lmStudioService: LMStudioService,
) {
    private val mainWindow: MainWindow by lazy {
        MainWindow(projectService, chatService)
    }

    private val settingsWindow: SettingsWindow by lazy {
        SettingsWindow(settingService, llmCoordinator, ollamaService, lmStudioService)
    }

    private val projectSettingsWindow: ProjectSettingWindow by lazy {
        ProjectSettingWindow(projectService)
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
    }

    /**
     * Uvolní všechny prostředky
     */
    fun dispose() {
        mainWindow.dispose()
        settingsWindow.dispose()
        trayIconManager.dispose()
    }

    fun showProjectSettingWindow() {
        projectSettingsWindow.isVisible = true
    }
}
