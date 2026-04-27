package com.jervis.wear.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.jervis.wear.voice.WearVoiceSession

/**
 * Voice chat screen for Wear OS.
 *
 * Direct WebSocket voice session to Jervis server.
 * Shows real-time state: listening → recording → transcribing → responding → TTS.
 */
@Composable
fun ChatScreen(onBack: () -> Unit) {
    val session = WearVoiceSession.shared
    val state by session.state.collectAsState()
    val transcript by session.transcript.collectAsState()
    val responseText by session.responseText.collectAsState()
    val statusText by session.statusText.collectAsState()
    val context = LocalContext.current

    // Cleanup on leave
    DisposableEffect(Unit) {
        onDispose {
            if (state != WearVoiceSession.SessionState.IDLE) {
                session.stop()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (state) {
            WearVoiceSession.SessionState.IDLE -> {
                Spacer(Modifier.weight(1f))

                Text(
                    "Klepni a mluv",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { session.start(context) },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Začít mluvit",
                        modifier = Modifier.size(32.dp),
                    )
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = onBack,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                ) {
                    Text("Zpět")
                }
            }

            WearVoiceSession.SessionState.CONNECTING -> {
                Spacer(Modifier.weight(1f))
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Připojuji...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
            }

            WearVoiceSession.SessionState.LISTENING -> {
                Spacer(Modifier.weight(1f))

                // Green waveform icon
                Text("🟢", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Poslouchám...",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4CAF50),
                )

                // Show last response if available
                if (responseText.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            responseText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }

                // Stop button
                IconButton(
                    onClick = { session.stop() },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Zastavit",
                        tint = Color.Red,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            WearVoiceSession.SessionState.RECORDING -> {
                Spacer(Modifier.weight(1f))

                // Red recording indicator
                Text("🔴", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (transcript.isEmpty()) "Mluvíte..." else transcript.takeLast(40),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )

                Spacer(Modifier.weight(1f))

                IconButton(
                    onClick = { session.stop() },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Zastavit",
                        tint = Color.Red,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            WearVoiceSession.SessionState.PROCESSING -> {
                Spacer(Modifier.weight(1f))

                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    statusText.ifBlank { "Zpracovávám..." },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                if (transcript.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        transcript,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }

                Spacer(Modifier.weight(1f))
            }

            WearVoiceSession.SessionState.PLAYING_TTS -> {
                Spacer(Modifier.weight(1f))

                Text("🔊", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))

                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        responseText.ifEmpty { "Odpovídám..." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            WearVoiceSession.SessionState.ERROR -> {
                Spacer(Modifier.weight(1f))

                Text("⚠️", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    statusText.ifBlank { "Chyba" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(8.dp))

                Button(onClick = { session.start(context) }) {
                    Text("Zkusit znovu")
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = onBack,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                ) {
                    Text("Zpět")
                }
            }
        }
    }
}
