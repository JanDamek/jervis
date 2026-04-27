package com.jervis.ui

import androidx.compose.ui.window.ComposeWindow
import platform.AppKit.NSApp
import platform.AppKit.NSViewController
import platform.AppKit.NSBox
import kotlin.experimental.ExperimentalNativeApi

private var exceptionHookInstalled = false
private var composeWindow: ComposeWindow? = null

@OptIn(ExperimentalNativeApi::class)
fun MainViewController(serverBaseUrl: String): NSViewController {
    if (!exceptionHookInstalled) {
        exceptionHookInstalled = true
        setUnhandledExceptionHook { throwable ->
            println("KOTLIN_UNHANDLED_EXCEPTION: ${throwable::class.simpleName}: ${throwable.message}")
            throwable.printStackTrace()
        }
    }

    val window = ComposeWindow()
    window.setContent {
        JervisApp(serverBaseUrl = serverBaseUrl)
    }
    composeWindow = window

    val vc = NSViewController(null, null)
    vc.view = NSBox()
    return vc
}
