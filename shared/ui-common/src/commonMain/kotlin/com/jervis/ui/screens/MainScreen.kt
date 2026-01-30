package com.jervis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.ui.MainViewModel
import com.jervis.ui.design.JTopBar
import com.jervis.ui.MainScreenView as MainScreenViewInternal

/**
 * Main Screen - Simplified version for initial setup
 * TODO: Copy full implementation from mobile-app after testing
 */
@Composable
fun MainScreen(viewModel: MainViewModel, onNavigate: (com.jervis.ui.navigation.Screen) -> Unit = {}) {
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
        connectionState = connectionState.name,
        queueSize = queueSize,
        runningProjectId = runningProjectId,
        runningProjectName = runningProjectName,
        runningTaskPreview = runningTaskPreview,
        onClientSelected = viewModel::selectClient,
        onProjectSelected = { id -> viewModel.selectProject(id ?: "") },
        onInputChanged = viewModel::updateInputText,
        onSendClick = viewModel::sendMessage,
        onNavigate = onNavigate,
        onReconnectClick = viewModel::manualReconnect
    )
}
