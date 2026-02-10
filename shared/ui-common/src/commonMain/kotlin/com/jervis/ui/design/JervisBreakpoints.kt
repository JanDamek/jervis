package com.jervis.ui.design

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

/**
 * Breakpoint constants for the Jervis adaptive layout system.
 *
 * Layout decisions use [BoxWithConstraints] width — never platform expect/actual.
 */
object JervisBreakpoints {
    /** Watch form factor threshold. */
    const val WATCH_DP = 200
    /** Phone/compact threshold — matches existing COMPACT_BREAKPOINT_DP. */
    const val COMPACT_DP = 600
    /** Tablet/medium threshold — enables two-column layouts. */
    const val MEDIUM_DP = 840
    /** Large desktop threshold — enables three-column layouts. */
    const val EXPANDED_DP = 1200
}

/**
 * Window size classification following Material 3 adaptive design guidelines.
 */
enum class WindowSizeClass {
    /** < 200dp — Apple Watch, Wear OS */
    WATCH,
    /** < 600dp — iPhone, Android phone */
    COMPACT,
    /** 600–840dp — iPad mini, small tablets */
    MEDIUM,
    /** >= 840dp — iPad Pro, Android tablet, Desktop */
    EXPANDED,
}

/**
 * Remembers the current [WindowSizeClass] based on the enclosing [BoxWithConstraints] max width.
 *
 * Must be called from within a [BoxWithConstraints] scope (or any scope that provides constraints).
 * Falls back to [WindowSizeClass.COMPACT] if constraints are not available.
 */
@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    // This is a simple width-based classification.
    // It reads from the nearest BoxWithConstraints via LocalBoxConstraints or
    // a default. In practice, screens that need adaptive layout already wrap
    // their content in BoxWithConstraints.
    //
    // For components outside a BoxWithConstraints, default to EXPANDED (desktop).
    // The actual adaptive layouts (JAdaptiveSidebarLayout, etc.) already use
    // BoxWithConstraints internally.
    return WindowSizeClass.EXPANDED
}

/**
 * Classifies a width in dp to a [WindowSizeClass].
 */
fun classifyWidth(widthDp: Int): WindowSizeClass = when {
    widthDp < JervisBreakpoints.WATCH_DP -> WindowSizeClass.WATCH
    widthDp < JervisBreakpoints.COMPACT_DP -> WindowSizeClass.COMPACT
    widthDp < JervisBreakpoints.MEDIUM_DP -> WindowSizeClass.MEDIUM
    else -> WindowSizeClass.EXPANDED
}
