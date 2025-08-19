package com.jervis.ui.window.trayui

import com.jervis.service.controller.ChatService
import com.jervis.service.llm.LlmCoordinator
import com.jervis.service.llm.lmstudio.LMStudioService
import com.jervis.service.llm.ollama.OllamaService
import com.jervis.service.project.ProjectService
import com.jervis.service.setting.SettingService
import com.jervis.service.client.ClientService
import com.jervis.service.client.ClientProjectLinkService
import com.jervis.ui.window.TrayIconManager
import org.springframework.stereotype.Service
import java.awt.SystemTray

/**
 * Service for managing the system tray UI.
 * This service provides a system tray icon with GUI for project and key management.
 */
@Service
class TrayUiManager(
    private val projectService: ProjectService,
    private val settingService: SettingService,
    private val chatService: ChatService,
    private val llmCoordinator: LlmCoordinator,
    private val ollamaService: OllamaService,
    private val lmStudioService: LMStudioService,
    private val clientService: ClientService,
    private val linkService: ClientProjectLinkService,
) {
    private var trayIconManager: TrayIconManager? = null

    /**
     * Initialize the tray UI
     */
    fun initialize() {
        if (!SystemTray.isSupported()) {
            println("System tray is not supported on this platform")
            return
        }

        // Create a window manager that will be used by the tray icon
        createWindowManager()

        // Create the tray icon manager
        // In a real implementation, this would create a TrayIconManager
        // For now, we'll just log that it would be created
        println("Would create TrayIconManager with windowManager")

        println("Tray UI initialized")
    }

    /**
     * Create a window manager for the tray UI
     */
    private fun createWindowManager(): TrayWindowManager =
        TrayWindowManager(projectService, settingService, chatService, llmCoordinator, ollamaService, lmStudioService, clientService, linkService)

    /**
     * Dispose of the tray UI
     */
    fun dispose() {
        trayIconManager?.dispose()
        trayIconManager = null
    }
}
