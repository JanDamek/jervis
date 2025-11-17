package com.jervis.ui

import androidx.compose.runtime.*
import com.jervis.dto.events.DebugEventDto
import kotlinx.coroutines.flow.Flow
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

/**
 * iOS lifecycle-aware DebugEventsProvider
 * Starts WebSocket when app is in foreground, stops in background
 */
@Composable
fun rememberLifecycleAwareDebugProvider(serverBaseUrl: String): DebugEventsProvider {
    val debugWebSocketClient = remember {
        DebugWebSocketClient(serverBaseUrl)
    }

    DisposableEffect(Unit) {
        val notificationCenter = NSNotificationCenter.defaultCenter

        // Observer for app becoming active (foreground)
        val activeObserver = notificationCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = null
        ) { _ ->
            debugWebSocketClient.start()
        }

        // Observer for app entering background
        val backgroundObserver = notificationCenter.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = null
        ) { _ ->
            debugWebSocketClient.stop()
        }

        // Start initially if app is active
        debugWebSocketClient.start()

        onDispose {
            notificationCenter.removeObserver(activeObserver)
            notificationCenter.removeObserver(backgroundObserver)
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
