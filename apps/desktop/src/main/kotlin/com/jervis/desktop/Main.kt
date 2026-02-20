package com.jervis.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jervis.desktop.ui.MainContent
import java.awt.Dimension

/**
 * Desktop Application Entry Point
 * Launches Jervis UI in a Compose Desktop window with multiple windows
 *
 * All configuration happens in the desktop app - no web admin.
 */
fun main() {
    // Set global exception handler to prevent UI crashes
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        System.err.println("Uncaught exception in thread ${thread.name}: ${throwable.message}")
        throwable.printStackTrace()
        // Don't crash the app - just log the error
    }

    application {
        val serverBaseUrl =
            System.getProperty("jervis.server.url")
                ?: "https://jervis.damek-soft.eu/"

        // Window state
        var showMainWindow by remember { mutableStateOf(true) }
        var showDebug by remember { mutableStateOf(false) }

        // Set dock icon and click handler on macOS
        LaunchedEffect(Unit) {
            MacOSUtils.setDockIcon()
            MacOSUtils.setDockIconClickHandler {
                showMainWindow = true
            }
        }

        // Connection manager with automatic retry
        val connectionManager = rememberConnectionManager(serverBaseUrl)

        // Shared navigator for main window navigation
        val navigator =
            remember {
                com.jervis.ui.navigation
                    .AppNavigator()
            }

        // Error notifications popup
        val errorNotifications = connectionManager.errorNotifications
        val lastError = errorNotifications.lastOrNull()
        var dismissedErrorId by remember { mutableStateOf<String?>(null) }

        // Tray Icon - minimizes to system tray
        @Suppress("DEPRECATION")
        Tray(
            icon = painterResource("icons/jervis_icon.png"),
            tooltip = "JERVIS Assistant",
            onAction = { showMainWindow = true },
            menu = {
                Item("Open Main Window", onClick = {
                    showMainWindow = true
                    navigator.navigateTo(com.jervis.ui.navigation.Screen.Main)
                })
                Separator()
                Item("User Tasks", onClick = {
                    showMainWindow = true
                    navigator.navigateTo(com.jervis.ui.navigation.Screen.UserTasks)
                })
                Item("Error Logs", onClick = {
                    showMainWindow = true
                    navigator.navigateTo(com.jervis.ui.navigation.Screen.ErrorLogs)
                })
                Separator()
                Item("RAG Search", onClick = {
                    showMainWindow = true
                    navigator.navigateTo(com.jervis.ui.navigation.Screen.RagSearch)
                })
                Item("Scheduler", onClick = {
                    showMainWindow = true
                    navigator.navigateTo(com.jervis.ui.navigation.Screen.Scheduler)
                })
                Separator()
                Item("Debug Console", onClick = { showDebug = true })
                Item("Settings", onClick = {
                    showMainWindow = true
                    navigator.navigateTo(com.jervis.ui.navigation.Screen.Settings)
                })
                Separator()
                Item("Exit", onClick = ::exitApplication)
            },
        )

        // Main Window
        if (showMainWindow) {
            Window(
                onCloseRequest = { showMainWindow = false },
                title = "JERVIS Assistant",
                state = rememberWindowState(width = 1200.dp, height = 800.dp),
            ) {
                window.minimumSize = Dimension(800, 600)

                MenuBar {
                    Menu("File") {
                        Item("Settings", onClick = { navigator.navigateTo(com.jervis.ui.navigation.Screen.Settings) })
                        Separator()
                        Item("Exit", onClick = { exitApplication() })
                    }
                    Menu("View") {
                        Item("Home", onClick = { navigator.navigateTo(com.jervis.ui.navigation.Screen.Main) })
                        Separator()
                        Item(
                            "User Tasks",
                            onClick = { navigator.navigateTo(com.jervis.ui.navigation.Screen.UserTasks) },
                        )
                        Item(
                            "Error Logs",
                            onClick = { navigator.navigateTo(com.jervis.ui.navigation.Screen.ErrorLogs) },
                        )
                        Separator()
                        Item(
                            "RAG Search",
                            onClick = { navigator.navigateTo(com.jervis.ui.navigation.Screen.RagSearch) },
                        )
                        Item("Scheduler", onClick = { navigator.navigateTo(com.jervis.ui.navigation.Screen.Scheduler) })
                        Separator()
                        Item("Debug Console", onClick = { showDebug = true })
                    }
                    Menu("Help") {
                        Item("About") {
                            // TODO: Show about dialog
                        }
                    }
                }

                // Main chat interface — always shown, offline handled gracefully
                MainContent(
                    repository = connectionManager.repository,
                    connectionManager = connectionManager.rpcConnectionManager,
                    navigator = navigator,
                    onOpenDebugWindow = { showDebug = true },
                )
            }
        }
    }
}

