package com.jervis.mobile

import androidx.compose.ui.window.ComposeUIViewController
import com.jervis.ui.JervisApp
import platform.UIKit.UIViewController

/**
 * iOS Application Entry Point
 * Creates UIViewController with Compose UI
 */
fun MainViewController(serverBaseUrl: String = "http://localhost:5500"): UIViewController {
    return ComposeUIViewController {
        JervisApp(serverBaseUrl = serverBaseUrl)
    }
}
