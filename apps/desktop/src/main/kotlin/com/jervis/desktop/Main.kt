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
fun main() = application {
    // Set dock icon on macOS
    MacOSUtils.setDockIcon()

    val serverBaseUrl = System.getProperty("jervis.server.url") ?: "http://localhost:5500/"

    // Connection manager with automatic retry
    val connectionManager = rememberConnectionManager(serverBaseUrl)
    val repository = connectionManager.repository

    // Window state
    var showMainWindow by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var settingsInitialTab by remember { mutableStateOf(0) }
    var showUserTasks by remember { mutableStateOf(false) }
    var showErrorLogs by remember { mutableStateOf(false) }
    var showRagSearch by remember { mutableStateOf(false) }
    var showScheduler by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }

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
            Item("Open Main Window", onClick = { showMainWindow = true })
            Separator()
            Item("Projects", onClick = { settingsInitialTab = 1; showSettings = true })
            Item("Clients", onClick = { settingsInitialTab = 0; showSettings = true })
            Separator()
            Item("User Tasks", onClick = { showUserTasks = true })
            Item("Error Logs", onClick = { showErrorLogs = true })
            Separator()
            Item("RAG Search", onClick = { showRagSearch = true })
            Item("Scheduler", onClick = { showScheduler = true })
            Separator()
            Item("Debug Console", onClick = { showDebug = true })
            Item("Settings", onClick = { showSettings = true })
            Separator()
            Item("Exit", onClick = ::exitApplication)
        }
    )

    // Main Window
    if (showMainWindow) {
        Window(
            onCloseRequest = { showMainWindow = false },
            title = "JERVIS Assistant",
            state = rememberWindowState(width = 1200.dp, height = 800.dp)
        ) {
        window.minimumSize = Dimension(800, 600)

        MenuBar {
            Menu("File") {
                Item("Settings", onClick = { showSettings = true })
                Separator()
                Item("Exit", onClick = { exitApplication() })
            }
            Menu("View") {
                Item("Projects", onClick = { settingsInitialTab = 1; showSettings = true })
                Item("Clients", onClick = { settingsInitialTab = 0; showSettings = true })
                Separator()
                Item("User Tasks", onClick = { showUserTasks = true })
                Item("Error Logs", onClick = { showErrorLogs = true })
                Separator()
                Item("RAG Search", onClick = { showRagSearch = true })
                Item("Scheduler", onClick = { showScheduler = true })
                Separator()
                Item("Debug Console", onClick = { showDebug = true })
            }
            Menu("Help") {
                Item("About") {
                    // TODO: Show about dialog
                }
            }
        }

            // Main chat interface - show connection status if not connected
            if (repository != null) {
                MainContent(repository = repository)
            } else {
                ConnectionStatusScreen(connectionManager.status, serverBaseUrl)
            }
        }
    }

    // Settings Window
    if (showSettings && repository != null) {
        Window(
            onCloseRequest = { showSettings = false },
            title = "Settings",
            state = rememberWindowState(width = 900.dp, height = 700.dp)
        ) {
            SettingsWindow(repository = repository, initialTabIndex = settingsInitialTab)
        }
    }


    // User Tasks Window
    if (showUserTasks && repository != null) {
        Window(
            onCloseRequest = { showUserTasks = false },
            title = "User Tasks",
            state = rememberWindowState(width = 800.dp, height = 600.dp)
        ) {
            UserTasksWindow(repository = repository)
        }
    }

    // Error Logs Window
    if (showErrorLogs && repository != null) {
        Window(
            onCloseRequest = { showErrorLogs = false },
            title = "Error Logs",
            state = rememberWindowState(width = 900.dp, height = 700.dp)
        ) {
            ErrorLogsWindow(repository = repository)
        }
    }

    // RAG Search Window
    if (showRagSearch && repository != null) {
        Window(
            onCloseRequest = { showRagSearch = false },
            title = "RAG Search",
            state = rememberWindowState(width = 800.dp, height = 600.dp)
        ) {
            RagSearchWindow(repository = repository)
        }
    }

    // Scheduler Window
    if (showScheduler && repository != null) {
        Window(
            onCloseRequest = { showScheduler = false },
            title = "Scheduler",
            state = rememberWindowState(width = 900.dp, height = 700.dp)
        ) {
            SchedulerWindow(repository = repository)
        }
    }

    // Debug Window
    if (showDebug) {
        Window(
            onCloseRequest = { showDebug = false },
            title = "Debug Console - LLM Calls",
            state = rememberWindowState(width = 1000.dp, height = 700.dp)
        ) {
            DebugWindow(connectionManager = connectionManager)
        }
    }

    // Error notification popup - show immediately when new error arrives
    if (lastError != null && lastError.timestamp != dismissedErrorId) {
        DialogWindow(
            onCloseRequest = { dismissedErrorId = lastError.timestamp },
            title = "Error Notification"
        ) {
            ErrorNotificationDialog(
                error = lastError,
                onDismiss = { dismissedErrorId = lastError.timestamp },
                onViewAllErrors = {
                    dismissedErrorId = lastError.timestamp
                    showErrorLogs = true
                }
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
    onViewAllErrors: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                // Error icon and title
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "❌",
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                    Column {
                        Text(
                            "Error Occurred",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            error.timestamp,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))

                // Error message
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Message:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            error.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                // Stack trace (if available)
                if (error.stackTrace != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                            Text(
                                "Stack Trace:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = 8.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                BasicText(
                                    text = error.stackTrace!!,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    )
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Action buttons
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onViewAllErrors,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("View All Errors")
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
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
fun ConnectionStatusScreen(status: ConnectionStatus, serverUrl: String) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
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
                        color = MaterialTheme.colorScheme.error
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
