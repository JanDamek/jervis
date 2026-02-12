package com.jervis.ui.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp

/**
 * Confirm dialog with Czech defaults. Supports keyboard shortcuts (Enter/Escape).
 */
@Composable
fun JConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmText: String = "Potvrdit",
    dismissText: String = "Zrušit",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = false,
) {
    if (!visible) return

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(visible) {
        if (visible) focusRequester.requestFocus()
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
                                Key.Enter, Key.Spacebar -> { onConfirm(); true }
                                Key.Escape -> { onDismiss(); true }
                                else -> false
                            }
                        } else false
                    },
            ) {
                if (isDestructive) {
                    JDestructiveButton(onClick = onConfirm) { Text(confirmText) }
                } else {
                    JPrimaryButton(onClick = onConfirm) { Text(confirmText) }
                }
            }
        },
        dismissButton = {
            JSecondaryButton(onClick = onDismiss) { Text(dismissText) }
        },
    )
}

/**
 * Form dialog with scrollable content, confirm/dismiss buttons.
 */
@Composable
fun JFormDialog(
    visible: Boolean,
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "Potvrdit",
    dismissText: String = "Zrušit",
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap),
            ) {
                content()
            }
        },
        confirmButton = {
            JPrimaryButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmText) }
        },
        dismissButton = {
            JTextButton(onClick = onDismiss) { Text(dismissText) }
        },
    )
}

/**
 * Selection dialog for choosing from a list.
 */
@Composable
fun <T> JSelectionDialog(
    visible: Boolean,
    title: String,
    items: List<T>,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    itemContent: @Composable (T) -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                modifier = Modifier.heightIn(max = 400.dp),
            ) {
                items(items) { item ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(item) },
                    ) {
                        itemContent(item)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            JTextButton(onClick = onDismiss) { Text("Zrušit") }
        },
    )
}
