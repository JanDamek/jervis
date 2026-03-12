package com.jervis.wear.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

@Composable
fun HomeScreen(
    onNavigateToRecording: () -> Unit,
    onNavigateToChat: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Jervis",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Ad-hoc recording button
        Button(
            onClick = onNavigateToRecording,
            modifier = Modifier.fillMaxWidth(0.8f),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFD32F2F),
            ),
        ) {
            Text("Ad-hoc", textAlign = TextAlign.Center)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Chat voice button
        Button(
            onClick = onNavigateToChat,
            modifier = Modifier.fillMaxWidth(0.8f),
        ) {
            Text("Chat", textAlign = TextAlign.Center)
        }
    }
}
