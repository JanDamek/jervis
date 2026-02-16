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

    // Agent workload / activity log
    object AgentWorkload : Screen()

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
 * Stack-based navigator for app navigation.
 * Maintains a back-stack so goBack() returns to previous screen (not always Main).
 */
class AppNavigator {
    private val _backStack = mutableListOf<Screen>()
    private val _currentScreen = MutableStateFlow<Screen>(Screen.Main)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    fun navigateTo(screen: Screen) {
        if (_currentScreen.value != screen) {
            _backStack.add(_currentScreen.value)
            _currentScreen.value = screen
            _canGoBack.value = _backStack.isNotEmpty()
        }
    }

    fun goBack() {
        if (_backStack.isNotEmpty()) {
            _currentScreen.value = _backStack.removeLast()
            _canGoBack.value = _backStack.isNotEmpty()
        }
    }

    /** Navigate to screen, clearing entire history (e.g., menu resets to fresh). */
    fun navigateAndClearHistory(screen: Screen) {
        _backStack.clear()
        _currentScreen.value = screen
        _canGoBack.value = false
    }
}
