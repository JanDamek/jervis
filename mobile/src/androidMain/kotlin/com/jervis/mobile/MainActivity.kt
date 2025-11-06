package com.jervis.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.jervis.mobile.ui.App

/**
 * Main Activity for Android app
 * Hosts the Compose Multiplatform UI
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TODO: Load bootstrap config from SharedPreferences or BuildConfig
        val bootstrap =
            MobileBootstrap(
                serverBaseUrl = "http://10.0.2.2:8080", // Android emulator loopback to host
                clientId = "", // Will be selected by user
                defaultProjectId = null,
            )

        setContent {
            App(
                bootstrap = bootstrap,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
