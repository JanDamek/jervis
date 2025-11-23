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
            val serverBaseUrl = "https://home.damek-soft.eu:5500/"
            val debugProvider = rememberLifecycleAwareDebugProvider(serverBaseUrl)

            JervisApp(
                serverBaseUrl = serverBaseUrl,
                debugEventsProvider = debugProvider,
            )
        }
    }
}
