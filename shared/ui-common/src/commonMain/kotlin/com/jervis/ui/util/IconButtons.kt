package com.jervis.ui.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import com.jervis.ui.design.JAddButton
import com.jervis.ui.design.JDeleteButton
import com.jervis.ui.design.JEditButton
import com.jervis.ui.design.JRefreshButton

/**
 * Backward-compatible icon buttons delegating to the Jervis Design System.
 * Prefer using J-prefixed components directly in new code.
 */
@Composable
fun RefreshIconButton(onClick: () -> Unit, enabled: Boolean = true) {
    JRefreshButton(onClick = onClick, enabled = enabled)
}

@Composable
fun DeleteIconButton(onClick: () -> Unit, enabled: Boolean = true) {
    JDeleteButton(onClick = onClick, enabled = enabled)
}

@Composable
fun EditIconButton(onClick: () -> Unit, enabled: Boolean = true) {
    JEditButton(onClick = onClick, enabled = enabled)
}

@Composable
fun AddIconButton(onClick: () -> Unit, enabled: Boolean = true) {
    JAddButton(onClick = onClick, enabled = enabled)
}
