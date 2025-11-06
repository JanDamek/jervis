package com.jervis.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.jervis.mobile.MobileBootstrap

/**
 * Root Compose Application
 * Entry point for both Android and iOS
 */
@Composable
fun App(
    bootstrap: MobileBootstrap,
    modifier: Modifier = Modifier,
) {
    val viewModel = remember { MainViewModel(bootstrap) }
    val snackbarHostState = remember { SnackbarHostState() }

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
        val clients by viewModel.clients.collectAsState()
        val projects by viewModel.projects.collectAsState()
        val selectedClientId by viewModel.selectedClientId.collectAsState()
        val selectedProjectId by viewModel.selectedProjectId.collectAsState()
        val chatMessages by viewModel.chatMessages.collectAsState()
        val inputText by viewModel.inputText.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()

        MainScreen(
            clients = clients,
            projects = projects,
            selectedClientId = selectedClientId,
            selectedProjectId = selectedProjectId,
            chatMessages = chatMessages,
            inputText = inputText,
            isLoading = isLoading,
            onClientSelected = viewModel::selectClient,
            onProjectSelected = viewModel::selectProject,
            onInputChanged = viewModel::updateInputText,
            onSendClick = viewModel::sendMessage,
            modifier = modifier,
        )

        SnackbarHost(hostState = snackbarHostState)
    }
}
