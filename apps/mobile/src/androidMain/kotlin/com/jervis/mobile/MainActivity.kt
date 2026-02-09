package com.jervis.mobile

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.jervis.ui.JervisApp
import com.jervis.ui.notification.AndroidContextHolder

/**
 * Android Application Entry Point
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize notification context holder
        AndroidContextHolder.initialize(this)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                1001,
            )
        }

        setContent {
            val serverBaseUrl = "https://jervis.damek-soft.eu"

            JervisApp(
                serverBaseUrl = serverBaseUrl,
            )
        }
    }
}
