package com.jervis.ui

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController
import kotlin.experimental.ExperimentalNativeApi

private var exceptionHookInstalled = false

@OptIn(ExperimentalNativeApi::class)
fun MainViewController(serverBaseUrl: String): UIViewController {
    if (!exceptionHookInstalled) {
        exceptionHookInstalled = true
        setUnhandledExceptionHook { throwable ->
            println("KOTLIN_UNHANDLED_EXCEPTION: ${throwable::class.simpleName}: ${throwable.message}")
            throwable.printStackTrace()
        }
    }

    return ComposeUIViewController {
        JervisApp(serverBaseUrl = serverBaseUrl)
    }
}
