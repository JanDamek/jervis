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
    val isOffline by viewModel.isOffline.collectAsState()
    val selectedClientId by viewModel.selectedClientId.collectAsState()
    val selectedProjectId by viewModel.selectedProjectId.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isInitialLoading by viewModel.isInitialLoading.collectAsState()
    val queueSize by viewModel.queueSize.collectAsState()
    val runningProjectId by viewModel.runningProjectId.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val compressionBoundaries by viewModel.compressionBoundaries.collectAsState()
    val attachments by viewModel.attachments.collectAsState()
    val pendingMessageInfo by viewModel.pendingMessageInfo.collectAsState()
    val workspaceInfo by viewModel.workspaceInfo.collectAsState()
    val orchestratorHealthy by viewModel.orchestratorHealthy.collectAsState()
    val orchestratorProgress by viewModel.orchestratorProgress.collectAsState()

    // Environment panel state
    val environments by viewModel.environments.collectAsState()
    val resolvedEnvId by viewModel.resolvedEnvId.collectAsState()
    val environmentStatuses by viewModel.environmentStatuses.collectAsState()
    val environmentPanelVisible by viewModel.environmentPanelVisible.collectAsState()
    val environmentPanelWidthFraction by viewModel.environmentPanelWidthFraction.collectAsState()
    val expandedEnvIds by viewModel.expandedEnvIds.collectAsState()
    val expandedComponentIds by viewModel.expandedComponentIds.collectAsState()
    val environmentLoading by viewModel.environmentLoading.collectAsState()
    val environmentError by viewModel.environmentError.collectAsState()

    MainScreenViewInternal(
        selectedClientId = selectedClientId,
        selectedProjectId = selectedProjectId,
        chatMessages = chatMessages,
        inputText = inputText,
        isLoading = isLoading || isInitialLoading,
        isOffline = isOffline,
        queueSize = queueSize,
        runningProjectId = runningProjectId,
        hasMore = hasMore,
        isLoadingMore = isLoadingMore,
        compressionBoundaries = compressionBoundaries,
        attachments = attachments,
        onInputChanged = viewModel::updateInputText,
        onSendClick = viewModel::sendMessage,
        onEditMessage = viewModel::editMessage,
        onLoadMore = viewModel::loadMoreHistory,
        onAttachFile = viewModel::attachFile,
        onRemoveAttachment = viewModel::removeAttachment,
        pendingMessageInfo = pendingMessageInfo,
        onRetryPending = viewModel::retrySendMessage,
        onCancelPending = viewModel::cancelRetry,
        workspaceInfo = workspaceInfo,
        onRetryWorkspace = viewModel::retryWorkspace,
        orchestratorHealthy = orchestratorHealthy,
        orchestratorProgress = orchestratorProgress,
        hasEnvironment = environments.isNotEmpty(),
        environmentPanelVisible = environmentPanelVisible,
        onToggleEnvironmentPanel = viewModel::toggleEnvironmentPanel,
        panelWidthFraction = environmentPanelWidthFraction,
        onPanelWidthChange = viewModel::updatePanelWidthFraction,
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
                onToggleEnv = viewModel::toggleEnvExpanded,
                onToggleComponent = viewModel::toggleComponentExpanded,
                onClose = viewModel::closeEnvironmentPanel,
                onRefresh = viewModel::refreshEnvironments,
            )
        },
    )
}
