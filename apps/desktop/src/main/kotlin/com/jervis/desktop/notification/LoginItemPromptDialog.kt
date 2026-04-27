package com.jervis.desktop.notification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LoginItemPromptDialog() {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (LoginItemPreferences.shouldPromptUser()) {
            visible = true
        }
    }

    if (!visible) return

    AlertDialog(
        onDismissRequest = { /* nezavírat na pozadí — user musí rozhodnout */ },
        title = { Text("Spouštět Jervis při loginu Macu?") },
        text = {
            Column {
                Text(
                    "Aby push notifikace fungovaly i bezprostředně po restartu Macu " +
                            "a aby šlo reagovat (Povolit / Zamítnout / odpovědět) přímo z banneru, " +
                            "doporučujeme spouštět Jervis automaticky při přihlášení."
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Volbu lze kdykoli změnit v System Settings → General → Login Items.",
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                LoginItemPreferences.applyUserChoice(true)
                visible = false
            }) {
                Text("Spouštět při loginu")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = {
                LoginItemPreferences.applyUserChoice(false)
                visible = false
            }) {
                Text("Ne, díky")
            }
        },
    )
}
