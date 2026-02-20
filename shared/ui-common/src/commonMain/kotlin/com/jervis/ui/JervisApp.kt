package com.jervis.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.jervis.di.OfflineException
import com.jervis.di.RpcConnectionManager
import com.jervis.repository.JervisRepository

/**
 * Main Jervis Application Composable
 * Shared across Desktop, Android, iOS
 *
 * Uses [RpcConnectionManager] for non-destructive reconnection —
 * the Compose tree is preserved across reconnects, only the RPC
 * streams are re-subscribed via resilientFlow.
 *
 * Repository is created eagerly — the services lambda is only called
 * when an RPC method is invoked, not at construction time. This allows
 * the app to render immediately (offline mode) while the connection
 * is being established in the background.
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

    // Repository uses delegated getters — always returns fresh service instances.
    // The lambda is only called when an actual RPC method is invoked, not at construction.
    // If offline, the lambda throws OfflineException which callers handle gracefully.
    val repository = remember(connectionManager) {
        JervisRepository {
            connectionManager.getServices()
                ?: throw OfflineException()
        }
    }

    // Launch main app immediately — no waiting for connection
    App(
        repository = repository,
        connectionManager = connectionManager,
        defaultClientId = defaultClientId,
        defaultProjectId = defaultProjectId,
    )
}
