package com.jervis.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.jervis.ui.JervisApp
import com.jervis.ui.rememberLifecycleAwareDebugProvider

/**
 * Android Application Entry Point
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // Use host machine from Android emulator via 10.0.2.2
            val serverBaseUrl = "http://10.0.2.2:5500/"

            // Create lifecycle-aware debug provider that starts/stops WebSocket based on app state
            val debugProvider = rememberLifecycleAwareDebugProvider(serverBaseUrl)

            JervisApp(
                serverBaseUrl = serverBaseUrl,
                debugEventsProvider = debugProvider,
            )
        }
    }
}
