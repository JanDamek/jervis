package com.jervis.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Navigation screens available in the app
 * All screens mirror Desktop windows (Desktop = multiple windows, Mobile = navigation)
 */
sealed class Screen {
    object Main : Screen()

    // Settings & Configuration
    object Settings : Screen()

    // Tasks & Scheduling
    object UserTasks : Screen()

    object Scheduler : Screen()

    object PendingTasks : Screen()

    // Search & Logs
    object RagSearch : Screen()

    object ErrorLogs : Screen()

    // Meetings
    object Meetings : Screen()

    // Indexing queue overview
    object IndexingQueue : Screen()

    // Environment viewer (K8s resource inspection) — legacy, use EnvironmentManager
    object EnvironmentViewer : Screen()

    // Environment manager (full CRUD + K8s inspection)
    data class EnvironmentManager(val initialEnvironmentId: String? = null) : Screen()

    // Debug console (WebSocket)
    object DebugConsole : Screen()
}

/**
 * Flat navigator — no back-stack, back always goes to Main (chat).
 *
 * Design decision: stack-based navigation caused crashes when back was pressed
 * during ad-hoc recording (navigated to stale screen, killed recording).
 * Flat model: navigateTo() sets screen, goBack() always returns to Main.
 * Simple, safe, no stale state.
 */
class AppNavigator {
    private val _currentScreen = MutableStateFlow<Screen>(Screen.Main)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    fun navigateTo(screen: Screen) {
        if (_currentScreen.value != screen) {
            _currentScreen.value = screen
            _canGoBack.value = screen != Screen.Main
        }
    }

    /** Always go to Main (chat). No stack, no stale screens. */
    fun goBack() {
        _currentScreen.value = Screen.Main
        _canGoBack.value = false
    }

    /** Navigate to screen (same as navigateTo — kept for API compat). */
    fun navigateAndClearHistory(screen: Screen) {
        _currentScreen.value = screen
        _canGoBack.value = screen != Screen.Main
    }
}
