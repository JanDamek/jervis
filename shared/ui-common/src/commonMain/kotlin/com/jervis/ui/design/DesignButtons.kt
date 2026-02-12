package com.jervis.ui.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.jervis.ui.util.ConfirmDialog

@Composable
fun JPrimaryButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        shape = MaterialTheme.shapes.medium,
        content = content,
    )
}

@Composable
fun JSecondaryButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        content = content,
    )
}

@Composable
fun JTextButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        content = content,
    )
}

@Composable
fun JDestructiveButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        shape = MaterialTheme.shapes.medium,
        content = content,
    )
}

@Composable
fun JRunTextButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    text: String = "Spustit",
) {
    JTextButton(onClick = onClick, enabled = enabled) { Text("▶ $text") }
}

@Composable
fun JIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(JervisSpacing.touchTarget),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}

@Composable
fun JRefreshButton(onClick: () -> Unit, enabled: Boolean = true) {
    JIconButton(onClick = onClick, icon = Icons.Default.Refresh, contentDescription = "Obnovit", enabled = enabled)
}

@Composable
fun JDeleteButton(onClick: () -> Unit, enabled: Boolean = true) {
    JIconButton(
        onClick = onClick,
        icon = Icons.Default.Delete,
        contentDescription = "Smazat",
        enabled = enabled,
        tint = MaterialTheme.colorScheme.error,
    )
}

@Composable
fun JEditButton(onClick: () -> Unit, enabled: Boolean = true) {
    JIconButton(onClick = onClick, icon = Icons.Default.Edit, contentDescription = "Upravit", enabled = enabled)
}

@Composable
fun JAddButton(onClick: () -> Unit, enabled: Boolean = true) {
    JIconButton(onClick = onClick, icon = Icons.Default.Add, contentDescription = "Přidat", enabled = enabled)
}

/**
 * Remove icon button with built-in confirmation dialog.
 * Click shows ConfirmDialog; onConfirmed fires only after user confirms.
 */
@Composable
fun JRemoveIconButton(
    onConfirmed: () -> Unit,
    title: String = "Odebrat?",
    message: String = "Položka bude odebrána.",
    confirmText: String = "Odebrat",
    contentDescription: String = "Odebrat",
) {
    var showConfirm by remember { mutableStateOf(false) }

    JIconButton(
        onClick = { showConfirm = true },
        icon = Icons.Default.Close,
        contentDescription = contentDescription,
    )

    ConfirmDialog(
        visible = showConfirm,
        title = title,
        message = message,
        confirmText = confirmText,
        onConfirm = { showConfirm = false; onConfirmed() },
        onDismiss = { showConfirm = false },
    )
}

@Composable
fun JWatchActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = JervisSpacing.watchTouchTarget),
        colors = if (isDestructive) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
