package com.jervis.module.trayui

import com.jervis.module.llmcoordinator.LlmCoordinator
import com.jervis.service.LMStudioService
import com.jervis.service.OllamaService
import com.jervis.service.ProjectService
import com.jervis.service.SettingService
import com.jervis.window.ApplicationWindowManager
import org.springframework.stereotype.Component

/**
 * Manager for tray UI windows.
 * This class manages the windows that can be opened from the system tray.
 */
@Component
class TrayWindowManager(
    private val projectService: ProjectService,
    private val settingService: SettingService,
    private val chatService: com.jervis.service.ChatService,
    private val llmCoordinator: LlmCoordinator,
    private val ollamaService: OllamaService,
    private val lmStudioService: LMStudioService,
) {
    private val windowManager = ApplicationWindowManager(settingService, projectService, chatService, llmCoordinator, ollamaService, lmStudioService)

    /**
     * Initialize the window manager
     *
     * @param startMinimized Whether to start with windows minimized
     */
    fun initialize(startMinimized: Boolean = true) {
        windowManager.initialize(startMinimized)
    }

    /**
     * Show the main window
     */
    fun showMainWindow() {
        windowManager.showMainWindow()
    }

    /**
     * Show the settings window
     */
    fun showSettingsWindow() {
        windowManager.showSettingsWindow()
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
