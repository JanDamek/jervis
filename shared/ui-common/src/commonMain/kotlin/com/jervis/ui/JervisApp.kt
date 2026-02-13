package com.jervis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.ui.design.JervisTheme
import com.jervis.di.RpcConnectionManager
import com.jervis.di.RpcConnectionState
import com.jervis.repository.JervisRepository

/**
 * Main Jervis Application Composable
 * Shared across Desktop, Android, iOS
 *
 * Uses [RpcConnectionManager] for non-destructive reconnection —
 * the Compose tree is preserved across reconnects, only the RPC
 * streams are re-subscribed via resilientFlow.
 *
 * @param serverBaseUrl Base URL of the Jervis server (e.g., "https://jervis.damek-soft.eu")
 * @param defaultClientId Optional default client ID
 * @param defaultProjectId Optional default project ID
 */
@Composable
fun JervisApp(
    serverBaseUrl: String,
    defaultClientId: String? = null,
    defaultProjectId: String? = null,
) {
    val connectionManager = remember(serverBaseUrl) { RpcConnectionManager(serverBaseUrl) }

    LaunchedEffect(connectionManager) {
        connectionManager.connect()
    }

    val connState by connectionManager.state.collectAsState()

    when (connState) {
        is RpcConnectionState.Disconnected,
        is RpcConnectionState.Connecting,
        -> {
            // Show loading screen only during initial connection.
            // After first connect, reconnections are handled transparently by resilientFlow.
            if (connectionManager.generation.value == 0L) {
                JervisTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                CircularProgressIndicator()
                                Text("Connecting to server...")
                                Text(
                                    text = serverBaseUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                return
            }
            // After initial connect, don't destroy the Compose tree during reconnection.
            // The overlay inside MainViewModel handles showing reconnection UI.
        }
        is RpcConnectionState.Connected -> {
            // Connection established — first time or after reconnect
        }
    }

    // Repository uses delegated getters — always returns fresh service instances
    val repository = remember(connectionManager) {
        JervisRepository {
            connectionManager.getServices()
                ?: error("Services accessed before connection established")
        }
    }

    // Launch main app — connectionManager handles reconnection, no onRefreshConnection needed
    App(
        repository = repository,
        connectionManager = connectionManager,
        defaultClientId = defaultClientId,
        defaultProjectId = defaultProjectId,
    )
}
