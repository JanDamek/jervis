package com.jervis.mobile

import androidx.compose.ui.window.ComposeUIViewController
import com.jervis.mobile.ui.App
import platform.UIKit.UIViewController

/**
 * iOS entry point - creates UIViewController with Compose content
 * This function is called from Swift/Xcode project
 */
fun MainViewController(bootstrap: MobileBootstrap): UIViewController =
    ComposeUIViewController {
        App(bootstrap = bootstrap)
    }
