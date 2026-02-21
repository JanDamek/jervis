package com.jervis.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.jervis.di.RpcConnectionManager
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JervisTheme
import com.jervis.ui.navigation.AppNavigator
import com.jervis.ui.navigation.Screen
import com.jervis.ui.notification.UserTaskNotificationDialog
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
    val offlineSyncService = remember(repository) {
        com.jervis.ui.meeting.OfflineMeetingSyncService(
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
        val currentScreen by appNavigator.currentScreen.collectAsState()
        val canGoBack by appNavigator.canGoBack.collectAsState()
        val connectionState by viewModel.connection.state.collectAsState()
        val isInitialLoading by viewModel.connection.isInitialLoading.collectAsState()

        // Global recording state
        val isRecordingGlobal by meetingViewModel.isRecording.collectAsState()
        val recordingDurationGlobal by meetingViewModel.recordingDuration.collectAsState()
        val uploadStateGlobal by meetingViewModel.uploadState.collectAsState()

        // Agent status
        val runningProjectId by viewModel.queue.runningProjectId.collectAsState()
        val runningTaskType by viewModel.queue.runningTaskType.collectAsState()
        val queueSize by viewModel.queue.queueSize.collectAsState()

        // Environment
        val environments by viewModel.environment.environments.collectAsState()

        // User task count for badge
        val userTaskCount by viewModel.notification.userTaskCount.collectAsState()

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
            onReconnect = viewModel.connection::manualReconnect,
            isRecording = isRecordingGlobal,
            recordingDuration = recordingDurationGlobal,
            onNavigateToMeetings = { appNavigator.navigateTo(Screen.Meetings) },
            onQuickRecord = { meetingViewModel.startQuickRecording() },
            onStopRecording = { meetingViewModel.stopRecording() },
            isAgentRunning = runningProjectId != null && runningProjectId != "none",
            runningTaskType = runningTaskType,
            queueSize = queueSize,
            onAgentStatusClick = { appNavigator.navigateTo(Screen.AgentWorkload) },
            hasEnvironment = environments.isNotEmpty(),
            onToggleEnvironmentPanel = viewModel.environment::togglePanel,
            userTaskCount = userTaskCount,
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
                    onRefreshBadge = { viewModel.notification.refreshUserTaskCount() },
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
                    offlineSyncService = offlineSyncService,
                )
            }

            Screen.IndexingQueue -> {
                IndexingQueueScreen(
                    repository = repository,
                    qualificationProgress = viewModel.queue.qualificationProgress,
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

        // User task notification dialog — shown for all user tasks (approval + clarification)
        val userTaskEvent by viewModel.notification.userTaskDialogEvent.collectAsState()
        userTaskEvent?.let { event ->
            UserTaskNotificationDialog(
                event = event,
                onApprove = { taskId -> viewModel.notification.approveTask(taskId, viewModel::reportError) },
                onDeny = { taskId, reason -> viewModel.notification.denyTask(taskId, reason, viewModel::reportError) },
                onReply = { taskId, reply ->
                    viewModel.notification.replyToTask(taskId, reply, viewModel::reportError)
                    // Navigate to the relevant project if projectId is available
                    event.projectId?.let { projectId ->
                        viewModel.selectClient(event.clientId)
                        viewModel.selectProject(projectId)
                        appNavigator.navigateAndClearHistory(Screen.Main)
                    }
                },
                onDismiss = { viewModel.notification.dismissUserTaskDialog() },
            )
        }

      }
    }
}
