package com.jervis.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Navigation screens available in the app.
 *
 * Minimal set: Main (chat), Meetings, Calendar, Settings, Dashboard.
 * All other views are sidebars or inline in chat.
 */
sealed class Screen {
    object Main : Screen()
    object Settings : Screen()
    object Meetings : Screen()
    object Calendar : Screen()
    /** Full-screen Assistant view: live transcript + hints during an active meeting. */
    object Assistant : Screen()
    /** Read-only orchestrator session metrics (PR-D1). */
    object Dashboard : Screen()
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
