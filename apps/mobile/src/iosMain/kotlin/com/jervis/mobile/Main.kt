package com.jervis.mobile

import androidx.compose.ui.window.ComposeUIViewController
import com.jervis.ui.JervisApp

/**
 * iOS Application Entry Point
 * Called from Swift to create the Compose UI
 */
fun MainViewController(serverBaseUrl: String) =
    ComposeUIViewController {
        JervisApp(
            serverBaseUrl = serverBaseUrl,
        )
    }
