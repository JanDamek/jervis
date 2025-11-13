package com.jervis.ui.util

import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Standardized icon-only buttons using emoji glyphs to avoid extra dependencies.
 * Replace with proper vector icons in the future if we add icon packs.
 */
@Composable
fun RefreshIconButton(onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) {
        Text("🔄", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun DeleteIconButton(onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) {
        Text("🗑️", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun EditIconButton(onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) {
        Text("✏️", style = MaterialTheme.typography.titleMedium)
    }
}
