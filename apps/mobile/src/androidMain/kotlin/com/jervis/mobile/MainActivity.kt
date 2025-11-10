package com.jervis.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.jervis.ui.JervisApp

/**
 * Android Application Entry Point
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // Android emulator: 10.0.2.2 is host machine localhost
            // Real device: use actual IP address
            val serverBaseUrl = "http://10.0.2.2:5500"

            JervisApp(
                serverBaseUrl = serverBaseUrl
            )
        }
    }
}
