package com.jervis.wear.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JervisWearApp()
        }
    }
}

@Composable
fun JervisWearApp() {
    val navController = rememberSwipeDismissableNavController()

    androidx.wear.compose.material3.MaterialTheme {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = "home",
        ) {
            composable("home") {
                HomeScreen(
                    onNavigateToRecording = { navController.navigate("recording") },
                    onNavigateToChat = { navController.navigate("chat") },
                )
            }
            composable("recording") {
                RecordingScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable("chat") {
                ChatScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
