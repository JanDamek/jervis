package com.jervis.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.jervis.di.RpcConnectionManager
import com.jervis.di.JervisRepository

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

    // Repository is constructed with the connection manager so `repository.call { }`
    // gets transparent wait-for-reconnect + OfflineException semantics.
    // Direct accessors (`repository.chat.sendMessage`, ...) still throw OfflineException
    // synchronously when disconnected — callers can catch or upgrade to `repository.call`.
    val repository = remember(connectionManager) {
        JervisRepository(connectionManager)
    }

    // Launch main app immediately — no waiting for connection
    App(
        repository = repository,
        connectionManager = connectionManager,
        defaultClientId = defaultClientId,
        defaultProjectId = defaultProjectId,
    )
}
