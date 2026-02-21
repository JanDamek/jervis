package com.jervis.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jervis.ui.MainViewModel
import com.jervis.ui.environment.EnvironmentPanel
import com.jervis.ui.MainScreenView as MainScreenViewInternal

@Composable
fun MainScreen(
    viewModel: MainViewModel,
) {
    val isOffline by viewModel.connection.isOffline.collectAsState()
    val selectedClientId by viewModel.selectedClientId.collectAsState()
    val selectedProjectId by viewModel.selectedProjectId.collectAsState()
    val chatMessages by viewModel.chat.chatMessages.collectAsState()
    val inputText by viewModel.chat.inputText.collectAsState()
    val isChatLoading by viewModel.chat.isLoading.collectAsState()
    val isInitialLoading by viewModel.connection.isInitialLoading.collectAsState()
    val queueSize by viewModel.queue.queueSize.collectAsState()
    val runningProjectId by viewModel.queue.runningProjectId.collectAsState()
    val hasMore by viewModel.chat.hasMore.collectAsState()
    val isLoadingMore by viewModel.chat.isLoadingMore.collectAsState()
    val compressionBoundaries by viewModel.chat.compressionBoundaries.collectAsState()
    val attachments by viewModel.chat.attachments.collectAsState()
    val pendingMessageInfo by viewModel.chat.pendingMessageInfo.collectAsState()
    val workspaceInfo by viewModel.workspaceInfo.collectAsState()
    val orchestratorHealthy by viewModel.queue.orchestratorHealthy.collectAsState()
    val orchestratorProgress by viewModel.queue.orchestratorProgress.collectAsState()

    // Environment panel state (delegated to EnvironmentViewModel)
    val environments by viewModel.environment.environments.collectAsState()
    val resolvedEnvId by viewModel.environment.resolvedEnvId.collectAsState()
    val environmentStatuses by viewModel.environment.environmentStatuses.collectAsState()
    val environmentPanelVisible by viewModel.environment.panelVisible.collectAsState()
    val environmentPanelWidthFraction by viewModel.environment.panelWidthFraction.collectAsState()
    val expandedEnvIds by viewModel.environment.expandedEnvIds.collectAsState()
    val expandedComponentIds by viewModel.environment.expandedComponentIds.collectAsState()
    val environmentLoading by viewModel.environment.loading.collectAsState()
    val environmentError by viewModel.environment.error.collectAsState()

    MainScreenViewInternal(
        selectedClientId = selectedClientId,
        selectedProjectId = selectedProjectId,
        chatMessages = chatMessages,
        inputText = inputText,
        isLoading = isChatLoading || isInitialLoading,
        isOffline = isOffline,
        queueSize = queueSize,
        runningProjectId = runningProjectId,
        hasMore = hasMore,
        isLoadingMore = isLoadingMore,
        compressionBoundaries = compressionBoundaries,
        attachments = attachments,
        onInputChanged = viewModel.chat::updateInputText,
        onSendClick = viewModel.chat::sendMessage,
        onEditMessage = viewModel.chat::editMessage,
        onLoadMore = viewModel.chat::loadMoreHistory,
        onAttachFile = viewModel.chat::attachFile,
        onRemoveAttachment = viewModel.chat::removeAttachment,
        pendingMessageInfo = pendingMessageInfo,
        onRetryPending = viewModel.chat::retrySendMessage,
        onCancelPending = viewModel.chat::cancelRetry,
        workspaceInfo = workspaceInfo,
        onRetryWorkspace = viewModel::retryWorkspace,
        orchestratorHealthy = orchestratorHealthy,
        orchestratorProgress = orchestratorProgress,
        hasEnvironment = environments.isNotEmpty(),
        environmentPanelVisible = environmentPanelVisible,
        onToggleEnvironmentPanel = viewModel.environment::togglePanel,
        panelWidthFraction = environmentPanelWidthFraction,
        onPanelWidthChange = viewModel.environment::updatePanelWidthFraction,
        environmentPanelContent = { isCompact ->
            EnvironmentPanel(
                environments = environments,
                statuses = environmentStatuses,
                resolvedEnvId = resolvedEnvId,
                isLoading = environmentLoading,
                error = environmentError,
                isCompact = isCompact,
                expandedEnvIds = expandedEnvIds,
                expandedComponentIds = expandedComponentIds,
                onToggleEnv = viewModel.environment::toggleEnvExpanded,
                onToggleComponent = viewModel.environment::toggleComponentExpanded,
                onClose = viewModel.environment::closePanel,
                onRefresh = viewModel.environment::refreshEnvironments,
            )
        },
    )
}
