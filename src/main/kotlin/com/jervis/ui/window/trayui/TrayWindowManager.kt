package com.jervis.ui.window.trayui

import com.jervis.service.controller.ChatService
import com.jervis.service.llm.LlmCoordinator
import com.jervis.service.llm.lmstudio.LMStudioService
import com.jervis.service.llm.ollama.OllamaService
import com.jervis.service.project.ProjectService
import com.jervis.service.setting.SettingService
import com.jervis.service.client.ClientService
import com.jervis.service.client.ClientProjectLinkService
import com.jervis.ui.component.ApplicationWindowManager
import org.springframework.stereotype.Component

/**
 * Manager for tray UI windows.
 * This class manages the windows that can be opened from the system tray.
 */
@Component
class TrayWindowManager(
    projectService: ProjectService,
    settingService: SettingService,
    chatService: ChatService,
    llmCoordinator: LlmCoordinator,
    ollamaService: OllamaService,
    lmStudioService: LMStudioService,
    clientService: ClientService,
    linkService: ClientProjectLinkService,
) {
    private val windowManager =
        ApplicationWindowManager(
            settingService,
            projectService,
            chatService,
            llmCoordinator,
            ollamaService,
            lmStudioService,
            clientService,
            linkService,
        )

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
