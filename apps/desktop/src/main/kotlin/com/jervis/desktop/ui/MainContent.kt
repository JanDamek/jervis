package com.jervis.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.jervis.repository.JervisRepository
import com.jervis.ui.App
import com.jervis.ui.navigation.AppNavigator
import com.jervis.ui.navigation.Screen

/**
 * Main content of the desktop application
 * Wraps the shared Compose UI from ui-common with navigator control
 */
@Composable
fun MainContent(
    repository: JervisRepository,
    navigator: AppNavigator,
    requestedScreen: Screen? = null,
    onOpenDebugWindow: () -> Unit = {},
    onRefreshConnection: (() -> Unit)? = null,
) {
    // Navigate to requested screen when it changes
    LaunchedEffect(requestedScreen) {
        requestedScreen?.let {
            navigator.navigateTo(it)
        }
    }

    // Use shared UI from ui-common with provided navigator
    App(
        repository = repository,
        defaultClientId = null,
        defaultProjectId = null,
        navigator = navigator,
        onOpenDebugWindow = onOpenDebugWindow,
        onRefreshConnection = onRefreshConnection,
    )
}
