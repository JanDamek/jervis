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

    // Set dock icon BEFORE Compose application starts
    // so Stage Manager / Mission Control sees it immediately
    MacOSUtils.setDockIcon()

    application {
        val serverBaseUrl =
            System.getProperty("jervis.server.url")
                ?: "https://jervis.damek-soft.eu/"

        // Window state
        var showMainWindow by remember { mutableStateOf(true) }
        var showDebug by remember { mutableStateOf(false) }

        // Register dock icon click handler on macOS
        LaunchedEffect(Unit) {
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
                Item("Meetings", onClick = {
                    showMainWindow = true
                    navigator.navigateTo(com.jervis.ui.navigation.Screen.Meetings)
                })
                Item("Calendar", onClick = {
                    showMainWindow = true
                    navigator.navigateTo(com.jervis.ui.navigation.Screen.Calendar)
                })
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
                icon = painterResource("icons/jervis_icon.png"),
                state = rememberWindowState(width = 1200.dp, height = 800.dp),
            ) {
                window.minimumSize = Dimension(800, 600)

                MenuBar {
                    Menu("File") {
                        Item("Settings", onClick = { navigator.navigateTo(com.jervis.ui.navigation.Screen.Settings) })
                        Item(
                            "Audio Loopback Device…",
                            onClick = {
                                // Machine-local preference — drives
                                // DesktopMeetingRecorder's ffmpeg input device
                                // when recording approved Teams/Meet/Zoom meetings.
                                val current = connectionManager.localSettings.getAudioLoopbackDevice() ?: ""
                                val input = javax.swing.JOptionPane.showInputDialog(
                                    null,
                                    "Enter the loopback device name used by ffmpeg to capture " +
                                        "system audio during approved meetings.\n\n" +
                                        "Examples:\n" +
                                        "  macOS:   BlackHole 2ch\n" +
                                        "  Windows: (empty = WASAPI default) or a dshow device name\n" +
                                        "  Linux:   default.monitor\n\n" +
                                        "Leave blank to use the OS default.",
                                    "Audio Loopback Device",
                                    javax.swing.JOptionPane.PLAIN_MESSAGE,
                                    null,
                                    null,
                                    current,
                                )
                                if (input != null) {
                                    connectionManager.localSettings.setAudioLoopbackDevice(input.toString())
                                }
                            },
                        )
                        Separator()
                        Item("Exit", onClick = { exitApplication() })
                    }
                    Menu("View") {
                        Item("Home", onClick = { navigator.navigateTo(com.jervis.ui.navigation.Screen.Main) })
                        Separator()
                        Item("Meetings", onClick = { navigator.navigateTo(com.jervis.ui.navigation.Screen.Meetings) })
                        Item("Calendar", onClick = { navigator.navigateTo(com.jervis.ui.navigation.Screen.Calendar) })
                        Separator()
                        Item("Settings", onClick = { navigator.navigateTo(com.jervis.ui.navigation.Screen.Settings) })
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

