package com.jervis.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object JervisSpacing {
    val outerPadding: Dp = 10.dp
    val sectionPadding: Dp = 12.dp
    val itemGap: Dp = 8.dp
    val touchTarget: Dp = 44.dp
    val sectionGap: Dp = 16.dp
    val fieldGap: Dp = 8.dp
    val watchTouchTarget: Dp = 56.dp
}

/** Backward-compat alias — use [JervisBreakpoints.COMPACT_DP] for new code. */
const val COMPACT_BREAKPOINT_DP = 600

@Composable
fun JervisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) JervisDarkColorScheme else JervisLightColorScheme
    val typography = JervisTypography.create()
    val semanticColors = if (darkTheme) {
        JervisSemanticColors(
            success = JervisColors.successDark,
            warning = JervisColors.warningDark,
            info = JervisColors.infoDark,
        )
    } else {
        JervisSemanticColors(
            success = JervisColors.success,
            warning = JervisColors.warning,
            info = JervisColors.info,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = JervisShapes,
    ) {
        CompositionLocalProvider(
            LocalJervisSemanticColors provides semanticColors,
        ) {
            content()
        }
    }
}
