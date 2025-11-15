package com.jervis.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * CompositionLocal that provides DebugEventsProvider implementation on platforms
 * Desktop uses its own window and passes provider directly; mobile provides it at the app root.
 */
val LocalDebugEventsProvider = staticCompositionLocalOf<DebugEventsProvider?> { null }
