package com.jervis.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.jervis.repository.JervisRepository
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.ui.tooling.preview.PreviewParameter

/**
 * Connections Screen - placeholder for shared UI
 * Desktop implements full UI in desktop module
 */
@Composable
fun ConnectionsScreen(
    repository: JervisRepository,
    onBack: () -> Unit
) {
    // Desktop has dedicated ConnectionsWindow, mobile can have simplified UI here
    Text("Connections management - see desktop app for full UI")
}
