package com.jervis.ui.util

import androidx.compose.runtime.Composable

/** Platform-specific screen keep-alive. Prevents screen from dimming/locking while in composition. */
@Composable
expect fun KeepScreenOn()
