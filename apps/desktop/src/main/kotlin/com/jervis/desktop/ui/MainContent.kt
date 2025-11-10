package com.jervis.desktop.ui

import androidx.compose.runtime.Composable
import com.jervis.repository.JervisRepository
import com.jervis.ui.App

/**
 * Main content of the desktop application
 * Wraps the shared Compose UI from ui-common
 */
@Composable
fun MainContent(repository: JervisRepository) {
    // Use shared UI from ui-common
    App(
        repository = repository,
        defaultClientId = null,
        defaultProjectId = null
    )
}
