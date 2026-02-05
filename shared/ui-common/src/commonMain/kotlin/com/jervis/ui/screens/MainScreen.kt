package com.jervis.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jervis.ui.MainViewModel
import com.jervis.ui.MainScreenView as MainScreenViewInternal

/**
 * Main Screen - Simplified version for initial setup
 * TODO: Copy full implementation from mobile-app after testing
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigate: (com.jervis.ui.navigation.Screen) -> Unit = {},
) {
    val clients by viewModel.clients.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val selectedClientId by viewModel.selectedClientId.collectAsState()
    val selectedProjectId by viewModel.selectedProjectId.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isInitialLoading by viewModel.isInitialLoading.collectAsState()
    val queueSize by viewModel.queueSize.collectAsState()
    val runningProjectId by viewModel.runningProjectId.collectAsState()
    val runningProjectName by viewModel.runningProjectName.collectAsState()
    val runningTaskPreview by viewModel.runningTaskPreview.collectAsState()

    MainScreenViewInternal(
        clients = clients,
        projects = projects,
        selectedClientId = selectedClientId,
        selectedProjectId = selectedProjectId,
        chatMessages = chatMessages,
        inputText = inputText,
        isLoading = isLoading || isInitialLoading,
        queueSize = queueSize,
        runningProjectId = runningProjectId,
        runningProjectName = runningProjectName,
        runningTaskPreview = runningTaskPreview,
        onClientSelected = viewModel::selectClient,
        onProjectSelected = { id -> viewModel.selectProject(id ?: "") },
        onInputChanged = viewModel::updateInputText,
        onSendClick = viewModel::sendMessage,
        onNavigate = onNavigate,
    )
}
