package com.jervis.ui.util

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.jervis.ui.design.JDestructiveButton
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JTextButton

/**
 * Confirm dialog with Czech defaults and keyboard support (Enter/Space/Escape).
 *
 * @param visible Whether the dialog is shown
 * @param title Dialog title
 * @param message Dialog message body
 * @param confirmText Confirm button label (default: "Smazat" for destructive, "Potvrdit" otherwise)
 * @param dismissText Dismiss button label (default: "Zrušit")
 * @param onConfirm Called when user confirms
 * @param onDismiss Called when user dismisses
 * @param isDestructive Whether the confirm action is destructive (red button)
 */
@Composable
fun ConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmText: String = "Smazat",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = true,
    dismissText: String = "Zrušit",
) {
    if (!visible) return

    val focusRequester = remember { FocusRequester() }

    // Request focus on the confirm button when dialog appears
    LaunchedEffect(visible) {
        if (visible) {
            focusRequester.requestFocus()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Box(
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            when (keyEvent.key) {
                                Key.Enter, Key.Spacebar -> {
                                    onConfirm()
                                    true
                                }
                                Key.Escape -> {
                                    onDismiss()
                                    true
                                }
                                else -> false
                            }
                        } else {
                            false
                        }
                    }
            ) {
                if (isDestructive) {
                    JDestructiveButton(onClick = onConfirm) {
                        Text(confirmText)
                    }
                } else {
                    JPrimaryButton(onClick = onConfirm) {
                        Text(confirmText)
                    }
                }
            }
        },
        dismissButton = {
            JTextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        }
    )
}
