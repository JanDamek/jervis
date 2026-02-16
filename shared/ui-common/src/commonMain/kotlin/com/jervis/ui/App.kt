package com.jervis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import com.jervis.di.RpcConnectionManager
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JervisTheme
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
    connectionManager: RpcConnectionManager,
    defaultClientId: String? = null,
    defaultProjectId: String? = null,
    modifier: Modifier = Modifier,
    navigator: AppNavigator? = null,
    onOpenDebugWindow: (() -> Unit)? = null,
) {
    val viewModel = remember(repository) { MainViewModel(repository, connectionManager, defaultClientId, defaultProjectId) }
    val meetingViewModel = remember(repository) {
        com.jervis.ui.meeting.MeetingViewModel(
            connectionManager = connectionManager,
            repository = repository,
        )
    }
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

    // Cleanup on dispose (keyed on viewModel so old one gets disposed on reconnect)
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.onDispose()
        }
    }

    JervisTheme {
      Surface(
          modifier = Modifier.fillMaxSize().safeDrawingPadding(),
          color = MaterialTheme.colorScheme.background,
      ) {
        val clients by viewModel.clients.collectAsState()
        val projects by viewModel.projects.collectAsState()
        val projectGroups by viewModel.projectGroups.collectAsState()
        val selectedClientId by viewModel.selectedClientId.collectAsState()
        val selectedProjectId by viewModel.selectedProjectId.collectAsState()
        val selectedGroupId by viewModel.selectedGroupId.collectAsState()
        val chatMessages by viewModel.chatMessages.collectAsState()
        val inputText by viewModel.inputText.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()
        val currentScreen by appNavigator.currentScreen.collectAsState()
        val canGoBack by appNavigator.canGoBack.collectAsState()
        val connectionState by viewModel.connectionState.collectAsState()
        val isOverlayVisible by viewModel.isOverlayVisible.collectAsState()
        val reconnectAttempt by viewModel.reconnectAttemptDisplay.collectAsState()
        val isInitialLoading by viewModel.isInitialLoading.collectAsState()

        // Global recording state
        val isRecordingGlobal by meetingViewModel.isRecording.collectAsState()
        val recordingDurationGlobal by meetingViewModel.recordingDuration.collectAsState()
        val uploadStateGlobal by meetingViewModel.uploadState.collectAsState()

        // Agent status
        val runningProjectId by viewModel.runningProjectId.collectAsState()
        val runningTaskType by viewModel.runningTaskType.collectAsState()
        val queueSize by viewModel.queueSize.collectAsState()

        // Environment
        val environments by viewModel.environments.collectAsState()

        Column(modifier = Modifier.fillMaxSize()) {
        // Persistent top bar — always visible
        PersistentTopBar(
            canGoBack = canGoBack,
            onBack = { appNavigator.goBack() },
            onNavigate = { screen ->
                if (screen == Screen.DebugConsole && onOpenDebugWindow != null) {
                    onOpenDebugWindow()
                } else {
                    appNavigator.navigateTo(screen)
                }
            },
            clients = clients,
            projects = projects,
            projectGroups = projectGroups,
            selectedClientId = selectedClientId,
            selectedProjectId = selectedProjectId,
            selectedGroupId = selectedGroupId,
            onClientSelected = viewModel::selectClient,
            onProjectSelected = { id -> viewModel.selectProject(id ?: "") },
            onGroupSelected = viewModel::selectGroup,
            connectionState = connectionState,
            onReconnect = viewModel::manualReconnect,
            isRecording = isRecordingGlobal,
            recordingDuration = recordingDurationGlobal,
            onNavigateToMeetings = { appNavigator.navigateTo(Screen.Meetings) },
            isAgentRunning = runningProjectId != null && runningProjectId != "none",
            runningTaskType = runningTaskType,
            queueSize = queueSize,
            onAgentStatusClick = { appNavigator.navigateTo(Screen.AgentWorkload) },
            hasEnvironment = environments.isNotEmpty(),
            onToggleEnvironmentPanel = viewModel::toggleEnvironmentPanel,
        )

        // Global recording bar — full controls when recording
        if (isRecordingGlobal) {
            com.jervis.ui.meeting.RecordingBar(
                durationSeconds = recordingDurationGlobal,
                uploadState = uploadStateGlobal,
                onStop = { meetingViewModel.stopRecording() },
                onNavigateToMeetings = { appNavigator.navigateTo(Screen.Meetings) },
                isOnMeetingsScreen = currentScreen == Screen.Meetings,
            )
        }

        when (val screen = currentScreen) {
            Screen.Main -> {
                MainScreen(
                    viewModel = viewModel,
                )
            }

            Screen.Settings -> {
                com.jervis.ui.screens.settings.SettingsScreen(
                    repository = repository,
                    onBack = { appNavigator.goBack() },
                    onNavigate = { screen -> appNavigator.navigateTo(screen) },
                )
            }

            Screen.UserTasks -> {
                UserTasksScreen(
                    repository = repository,
                    onBack = { appNavigator.goBack() },
                    onNavigateToProject = { clientId, projectId ->
                        viewModel.selectClient(clientId)
                        if (projectId != null) {
                            viewModel.selectProject(projectId)
                        }
                        appNavigator.navigateAndClearHistory(Screen.Main)
                    },
                )
            }

            Screen.PendingTasks -> {
                PendingTasksScreen(
                    repository = repository,
                    onBack = { appNavigator.goBack() },
                )
            }

            Screen.ErrorLogs -> {
                ErrorLogsScreen(
                    repository = repository,
                    onBack = { appNavigator.goBack() },
                )
            }

            Screen.RagSearch -> {
                RagSearchScreen(
                    repository = repository,
                    onBack = { appNavigator.goBack() },
                )
            }

            Screen.Scheduler -> {
                SchedulerScreen(
                    repository = repository,
                    onBack = { appNavigator.goBack() },
                )
            }

            Screen.AgentWorkload -> {
                AgentWorkloadScreen(
                    viewModel = viewModel,
                    onBack = { appNavigator.goBack() },
                )
            }

            Screen.Meetings -> {
                com.jervis.ui.meeting.MeetingsScreen(
                    viewModel = meetingViewModel,
                    clients = clients,
                    selectedClientId = selectedClientId,
                    selectedProjectId = selectedProjectId,
                    onBack = { appNavigator.goBack() },
                )
            }

            Screen.IndexingQueue -> {
                IndexingQueueScreen(
                    repository = repository,
                    onBack = { appNavigator.goBack() },
                )
            }

            Screen.EnvironmentViewer -> {
                EnvironmentViewerScreen(
                    repository = repository,
                    onBack = { appNavigator.goBack() },
                )
            }

            is Screen.EnvironmentManager -> {
                com.jervis.ui.screens.environment.EnvironmentManagerScreen(
                    repository = repository,
                    onBack = { appNavigator.goBack() },
                    initialEnvironmentId = screen.initialEnvironmentId,
                )
            }

            // Debug console removed - server does not publish debug WebSocket
            Screen.DebugConsole -> {
                // No debug window - navigate back to main
                LaunchedEffect(Unit) {
                    appNavigator.navigateAndClearHistory(Screen.Main)
                }
            }
        }
        } // Column

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
