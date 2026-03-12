package com.jervis.wear.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay

@Composable
fun RecordingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Pripraveno") }

    // Timer
    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (true) {
                delay(1000)
                elapsedSeconds++
            }
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            // Stop recording if active
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Duration display
        Text(
            text = formatDuration(elapsedSeconds),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = if (isRecording) Color.Red else MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (isRecording) {
            // Stop button
            Button(
                onClick = {
                    isRecording = false
                    status = "Odesilam..."
                    // Send stop command to phone via DataLayer
                    val messageClient = Wearable.getMessageClient(context)
                    // In production: send stop message + final audio
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        status = "Hotovo"
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            onBack()
                        }, 1000)
                    }, 500)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
            ) {
                Text("Stop")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        isRecording = true
                        elapsedSeconds = 0
                        status = "Nahravam..."
                        // Send start command to phone via DataLayer
                        val messageClient = Wearable.getMessageClient(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                ) {
                    Text("Rec")
                }

                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Text("Zpet")
                }
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%02d:%02d", m, s)
}
