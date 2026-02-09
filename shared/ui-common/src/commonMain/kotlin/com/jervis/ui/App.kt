package com.jervis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.repository.JervisRepository
import com.jervis.ui.navigation.AppNavigator
import com.jervis.ui.navigation.Screen
import com.jervis.ui.notification.ApprovalNotificationDialog
import com.jervis.ui.screens.*

/**
 * Root Compose Application
 * Entry point for Desktop, Android and iOS
 */
@Composable
fun App(
    repository: JervisRepository,
    defaultClientId: String? = null,
    defaultProjectId: String? = null,
    modifier: Modifier = Modifier,
    navigator: AppNavigator? = null,
    onOpenDebugWindow: (() -> Unit)? = null,
    onRefreshConnection: (() -> Unit)? = null,
) {
    val viewModel = remember { MainViewModel(repository, defaultClientId, defaultProjectId, onRefreshConnection) }
    val snackbarHostState = remember { SnackbarHostState() }
    val appNavigator = navigator ?: remember { AppNavigator() }

    // Observe error messages and show snackbar
    val errorMessage by viewModel.errorMessage.collectAsState()
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            viewModel.onDispose()
        }
    }

    MaterialTheme {
      Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background,
      ) {
        val clients by viewModel.clients.collectAsState()
        val projects by viewModel.projects.collectAsState()
        val selectedClientId by viewModel.selectedClientId.collectAsState()
        val selectedProjectId by viewModel.selectedProjectId.collectAsState()
        val chatMessages by viewModel.chatMessages.collectAsState()
        val inputText by viewModel.inputText.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()
        val currentScreen by appNavigator.currentScreen.collectAsState()
        val connectionState by viewModel.connectionState.collectAsState()
        val showReconnectDialog by viewModel.showReconnectDialog.collectAsState()
        val isOverlayVisible by viewModel.isOverlayVisible.collectAsState()
        val reconnectAttempt by viewModel.reconnectAttemptDisplay.collectAsState()
        val isInitialLoading by viewModel.isInitialLoading.collectAsState()

        when (val screen = currentScreen) {
            Screen.Main -> {
                MainScreen(
                    viewModel = viewModel,
                    onNavigate = { screen ->
                        // Desktop has separate debug window, mobile uses navigation
                        if (screen == Screen.DebugConsole && onOpenDebugWindow != null) {
                            onOpenDebugWindow()
                        } else {
                            appNavigator.navigateTo(screen)
                        }
                    }
                )
            }

            Screen.Settings -> {
                com.jervis.ui.screens.settings.SettingsScreen(
                    repository = repository,
                    onBack = { appNavigator.navigateTo(Screen.Main) },
                )
            }

            Screen.UserTasks -> {
                UserTasksScreen(
                    repository = repository,
                    onBack = { appNavigator.navigateTo(Screen.Main) },
                )
            }

            Screen.PendingTasks -> {
                PendingTasksScreen(
                    repository = repository,
                    onBack = { appNavigator.navigateTo(Screen.Main) },
                )
            }

            Screen.ErrorLogs -> {
                ErrorLogsScreen(
                    repository = repository,
                    onBack = { appNavigator.navigateTo(Screen.Main) },
                )
            }

            Screen.RagSearch -> {
                RagSearchScreen(
                    repository = repository,
                    onBack = { appNavigator.navigateTo(Screen.Main) },
                )
            }

            Screen.Scheduler -> {
                SchedulerScreen(
                    repository = repository,
                    onBack = { appNavigator.navigateTo(Screen.Main) },
                )
            }

            Screen.AgentWorkload -> {
                AgentWorkloadScreen(
                    viewModel = viewModel,
                    onBack = { appNavigator.navigateTo(Screen.Main) },
                )
            }

            Screen.Meetings -> {
                val meetingViewModel = remember {
                    com.jervis.ui.meeting.MeetingViewModel(
                        repository.meetings,
                        repository.projects,
                        repository.transcriptCorrections,
                        repository.notifications,
                    )
                }

                com.jervis.ui.meeting.MeetingsScreen(
                    viewModel = meetingViewModel,
                    clients = clients,
                    selectedClientId = selectedClientId,
                    selectedProjectId = selectedProjectId,
                    onBack = { appNavigator.navigateTo(Screen.Main) },
                )
            }

            // Debug console removed - server does not publish debug WebSocket
            Screen.DebugConsole -> {
                // No debug window - navigate back to main
                LaunchedEffect(Unit) {
                    appNavigator.navigateTo(Screen.Main)
                }
            }
        }

        SnackbarHost(hostState = snackbarHostState)

        // Approval notification dialog — shown when orchestrator needs approval
        val approvalEvent by viewModel.approvalDialogEvent.collectAsState()
        approvalEvent?.let { event ->
            ApprovalNotificationDialog(
                event = event,
                onApprove = { taskId -> viewModel.approveTask(taskId) },
                onDeny = { taskId, reason -> viewModel.denyTask(taskId, reason) },
                onDismiss = { viewModel.dismissApprovalDialog() },
            )
        }

        com.jervis.ui.util.ConfirmDialog(
            visible = showReconnectDialog,
            title = "Odeslání selhalo",
            message = "Zprávu se nepodařilo odeslat. Zkontrolujte připojení k internetu a zkuste to znovu.",
            confirmText = "Zkusit znovu",
            onConfirm = viewModel::retrySendMessage,
            onDismiss = viewModel::cancelRetry,
            isDestructive = false
        )

        if (isOverlayVisible) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { /* Nepovolíme zavření kliknutím mimo */ },
                properties = androidx.compose.ui.window.DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false
                )
            ) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(64.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 6.dp
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Text(
                            text = "Spojení ztraceno",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Server je momentálně nedostupný.\nPokouším se o znovupřipojení..." + 
                                   if (reconnectAttempt > 0) "\n(Pokus $reconnectAttempt)" else "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
      }
    }
}
