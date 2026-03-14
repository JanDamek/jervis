package com.jervis.mobile

import androidx.compose.ui.window.ComposeUIViewController
import com.jervis.ui.JervisApp
import kotlin.experimental.ExperimentalNativeApi

private var exceptionHookInstalled = false

/**
 * iOS Application Entry Point
 * Called from Swift to create the Compose UI
 */
@OptIn(ExperimentalNativeApi::class)
fun MainViewController(serverBaseUrl: String) = run {
    // Install global exception hook BEFORE creating any coroutines.
    // On Kotlin/Native, unhandled coroutine exceptions crash with SIGABRT.
    // This hook catches them and logs instead of crashing.
    if (!exceptionHookInstalled) {
        exceptionHookInstalled = true
        setUnhandledExceptionHook { throwable ->
            println("KOTLIN_UNHANDLED_EXCEPTION: ${throwable::class.simpleName}: ${throwable.message}")
            throwable.printStackTrace()
        }
    }

    ComposeUIViewController {
        JervisApp(
            serverBaseUrl = serverBaseUrl,
        )
    }
}
