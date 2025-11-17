package com.jervis.mobile

import androidx.compose.ui.window.ComposeUIViewController
import com.jervis.ui.JervisApp
import com.jervis.ui.rememberLifecycleAwareDebugProvider

/**
 * iOS Application Entry Point
 * Called from Swift to create the Compose UI
 */
fun MainViewController(serverBaseUrl: String = "http://home.damek-soft.eu:5500") =
    ComposeUIViewController {
        // Create lifecycle-aware debug provider that starts/stops WebSocket based on app state
        val debugProvider = rememberLifecycleAwareDebugProvider(serverBaseUrl)

        JervisApp(
            serverBaseUrl = serverBaseUrl,
            debugEventsProvider = debugProvider,
        )
    }
