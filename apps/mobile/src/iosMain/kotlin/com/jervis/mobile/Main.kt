package com.jervis.mobile

import androidx.compose.ui.window.ComposeUIViewController
import com.jervis.ui.JervisApp
import com.jervis.ui.rememberLifecycleAwareDebugProvider

/**
 * iOS Application Entry Point
 * Called from Swift to create the Compose UI
 */
fun MainViewController(serverBaseUrl: String) =
    ComposeUIViewController {
        val debugProvider = rememberLifecycleAwareDebugProvider(serverBaseUrl)

        JervisApp(
            serverBaseUrl = serverBaseUrl,
            debugEventsProvider = debugProvider,
        )
    }
