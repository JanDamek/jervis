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

    // Management
    object Clients : Screen()
    object Projects : Screen()

    // Settings & Configuration
    object Settings : Screen()

    // Tasks & Scheduling
    object UserTasks : Screen()
    object Scheduler : Screen()

    // Search & Logs
    object RagSearch : Screen()
    object ErrorLogs : Screen()

    // Indexing status overview and details
    object IndexingStatus : Screen()
    data class IndexingToolDetail(val toolKey: String) : Screen()

    // TODO: Desktop Debug Console (WebSocket) - not yet implemented for mobile
}

/**
 * Simple navigator for mobile app navigation
 * Desktop uses multiple windows, mobile uses single screen with navigation
 */
class AppNavigator {
    private val _currentScreen = MutableStateFlow<Screen>(Screen.Main)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    fun goBack() {
        navigateTo(Screen.Main)
    }
}
