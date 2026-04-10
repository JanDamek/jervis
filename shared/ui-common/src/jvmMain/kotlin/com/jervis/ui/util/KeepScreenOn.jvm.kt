package com.jervis.ui.util

import androidx.compose.runtime.Composable

@Composable
actual fun KeepScreenOn() {
    // No-op on desktop — screen management is OS-level
}
