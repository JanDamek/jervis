package com.jervis.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jervis.dto.events.DebugEventDto
import kotlinx.coroutines.flow.Flow

/**
 * Android lifecycle-aware DebugEventsProvider
 * Starts WebSocket when app is in foreground, stops in background
 */
@Composable
fun rememberLifecycleAwareDebugProvider(serverBaseUrl: String): DebugEventsProvider {
    val lifecycleOwner = LocalLifecycleOwner.current

    val debugWebSocketClient = remember {
        DebugWebSocketClient(serverBaseUrl)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    // App moved to foreground - start WebSocket
                    debugWebSocketClient.start()
                }
                Lifecycle.Event.ON_STOP -> {
                    // App moved to background - stop WebSocket
                    debugWebSocketClient.stop()
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            debugWebSocketClient.stop()
        }
    }

    return remember {
        object : DebugEventsProvider {
            override val debugEventsFlow: Flow<DebugEventDto>
                get() = debugWebSocketClient.debugEvents
        }
    }
}
