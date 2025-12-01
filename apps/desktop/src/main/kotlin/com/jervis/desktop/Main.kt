package com.jervis.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.jervis.desktop.ui.*
import java.awt.Dimension

/**
 * Desktop Application Entry Point
 * Launches Jervis UI in a Compose Desktop window with multiple windows
 *
 * All configuration happens in the desktop app - no web admin.
 */
fun main() =
    application {
        val serverBaseUrl = System.getProperty("jervis.server.url") ?: "https://home.damek-soft.eu:5500/"

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
        val repository = connectionManager.repository

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
                Item("Indexing Status", onClick = {
                    showMainWindow = true
                    navigator.navigateTo(com.jervis.ui.navigation.Screen.IndexingStatus)
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
                        Item("User Tasks", onClick = { navigator.navigateTo(com.jervis.ui.navigation.Screen.UserTasks) })
                        Item("Error Logs", onClick = { navigator.navigateTo(com.jervis.ui.navigation.Screen.ErrorLogs) })
                        Separator()
                        Item("RAG Search", onClick = { navigator.navigateTo(com.jervis.ui.navigation.Screen.RagSearch) })
                        Item("Scheduler", onClick = { navigator.navigateTo(com.jervis.ui.navigation.Screen.Scheduler) })
                        Separator()
                        Item("Debug Console", onClick = { showDebug = true })
                    }
                    Menu("Indexing") {
                        Item("Indexing Status", onClick = { navigator.navigateTo(com.jervis.ui.navigation.Screen.IndexingStatus) })
                    }
                    Menu("Help") {
                        Item("About") {
                            // TODO: Show about dialog
                        }
                    }
                }

                // Main chat interface - show connection status if not connected
                if (repository != null) {
                    MainContent(
                        repository = repository,
                        navigator = navigator,
                        onOpenDebugWindow = { showDebug = true },
                    )
                } else {
                    ConnectionStatusScreen(connectionManager.status, serverBaseUrl)
                }
            }
        }

        // Debug Window - desktop-only feature for monitoring LLM calls
        if (showDebug) {
            Window(
                onCloseRequest = { showDebug = false },
                title = "Debug Console - LLM Calls",
                state = rememberWindowState(width = 1000.dp, height = 700.dp),
            ) {
                com.jervis.ui.DebugWindow(eventsProvider = connectionManager)
            }
        }


        // Error notification popup - show immediately when new error arrives
        if (lastError != null && lastError.timestamp != dismissedErrorId) {
            DialogWindow(
                onCloseRequest = { dismissedErrorId = lastError.timestamp },
                title = "Error Notification",
            ) {
                ErrorNotificationDialog(
                    error = lastError,
                    onDismiss = { dismissedErrorId = lastError.timestamp },
                    onViewAllErrors = {
                        dismissedErrorId = lastError.timestamp
                        showMainWindow = true
                        navigator.navigateTo(com.jervis.ui.navigation.Screen.ErrorLogs)
                    },
                )
            }
        }
    }

/**
 * Error notification dialog
 */
@Composable
fun ErrorNotificationDialog(
    error: com.jervis.dto.events.ErrorNotificationEventDto,
    onDismiss: () -> Unit,
    onViewAllErrors: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                // Error icon and title
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "❌",
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                    Column {
                        Text(
                            "Error Occurred",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            error.timestamp,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))

                // Error message
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Message:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            error.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                // Stack trace (if available)
                if (error.stackTrace != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                            Text(
                                "Stack Trace:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(top = 8.dp)
                                        .verticalScroll(rememberScrollState()),
                            ) {
                                BasicText(
                                    text = error.stackTrace!!,
                                    style =
                                        MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                )
                            }
                        }
                    }
                }

                // Correlation ID (if available)
                if (error.correlationId != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Correlation ID: ${error.correlationId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Action buttons
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onViewAllErrors,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("View All Errors")
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}

/**
 * Connection status screen shown when not connected
 */
@Composable
fun ConnectionStatusScreen(
    status: ConnectionStatus,
    serverUrl: String,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (status) {
                is ConnectionStatus.Connecting -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Connecting to server...", style = MaterialTheme.typography.headlineSmall)
                    Text(serverUrl, style = MaterialTheme.typography.bodyMedium)
                }
                is ConnectionStatus.Disconnected -> {
                    Text("⚠️", style = MaterialTheme.typography.displayLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Unable to connect to server", style = MaterialTheme.typography.headlineSmall)
                    Text(serverUrl, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Error: ${status.error}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Retrying automatically...", style = MaterialTheme.typography.bodySmall)
                }
                is ConnectionStatus.Connected -> {
                    Text("Connected", style = MaterialTheme.typography.headlineSmall)
                }
                is ConnectionStatus.Offline -> {
                    Text("Offline", style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
}
