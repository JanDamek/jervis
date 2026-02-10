package com.jervis.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

/**
 * Semantic importance level for UI components.
 *
 * Used to prepare the design for future watch support.
 * Components tagged with [JImportance] will only render if the current
 * [WindowSizeClass] supports their level.
 *
 * Naming follows the W3C ARIA level pattern combined with Android's
 * NotificationCompat.PRIORITY_* convention.
 *
 * - [ESSENTIAL]: Must show on watch — approve/reject, status, mic toggle
 * - [IMPORTANT]: Should show on phone+ — forms, lists, primary data
 * - [DETAIL]: Desktop/expanded only — advanced settings, logs, debug info
 */
enum class ComponentImportance {
    ESSENTIAL,
    IMPORTANT,
    DETAIL,
}

val LocalComponentImportance = compositionLocalOf { ComponentImportance.DETAIL }

/**
 * Conditionally renders content based on the current [WindowSizeClass].
 *
 * - WATCH: only ESSENTIAL renders
 * - COMPACT: ESSENTIAL + IMPORTANT render
 * - MEDIUM/EXPANDED: everything renders
 */
@Composable
fun JImportance(
    level: ComponentImportance,
    content: @Composable () -> Unit,
) {
    val windowSize = rememberWindowSizeClass()
    val shouldRender = when (windowSize) {
        WindowSizeClass.WATCH -> level == ComponentImportance.ESSENTIAL
        WindowSizeClass.COMPACT -> level != ComponentImportance.DETAIL
        WindowSizeClass.MEDIUM, WindowSizeClass.EXPANDED -> true
    }
    if (shouldRender) {
        CompositionLocalProvider(LocalComponentImportance provides level) {
            content()
        }
    }
}
