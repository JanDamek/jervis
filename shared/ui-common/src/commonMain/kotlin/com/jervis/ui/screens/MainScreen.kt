package com.jervis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.ui.viewmodels.MainViewModel
import com.jervis.ui.design.JTopBar

/**
 * Main Screen - Simplified version for initial setup
 * TODO: Copy full implementation from mobile-app after testing
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
        topBar = {
            JTopBar(title = "JERVIS Assistant")
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when {
                state.isLoading -> {
                    com.jervis.ui.design.JCenteredLoading()
                }
                state.error != null -> {
                    com.jervis.ui.design.JErrorState(
                        message = "Error: ${state.error}",
                        onRetry = { viewModel.loadData() }
                    )
                }
                else -> {
                    Text(
                        text = "JERVIS is ready!",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Clients: ${state.clients.size}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Projects: ${state.projects.size}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { viewModel.loadData() }) {
                        Text("Refresh")
                    }
                }
            }
        }
    }
}
