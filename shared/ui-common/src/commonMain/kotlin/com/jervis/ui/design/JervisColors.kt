package com.jervis.ui.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic color tokens for Jervis Design System.
 *
 * Change colors here to re-theme the entire app.
 */
object JervisColors {
    // Semantic status colors (used by JStatusBadge, etc.)
    val success = Color(0xFF4CAF50)
    val successDark = Color(0xFF81C784)
    val warning = Color(0xFFFFC107)
    val warningDark = Color(0xFFFFD54F)
    val info = Color(0xFF2196F3)
    val infoDark = Color(0xFF64B5F6)
}

/**
 * Semantic status color holder provided via CompositionLocal.
 */
data class JervisSemanticColors(
    val success: Color,
    val warning: Color,
    val info: Color,
)

val LocalJervisSemanticColors = staticCompositionLocalOf {
    JervisSemanticColors(
        success = JervisColors.success,
        warning = JervisColors.warning,
        info = JervisColors.info,
    )
}

// Light theme
val JervisLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF545F71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E3F8),
    onSecondaryContainer = Color(0xFF111C2B),
    tertiary = Color(0xFF6E5676),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF7D8FF),
    onTertiaryContainer = Color(0xFF271430),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surface = Color(0xFFFAF9FD),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
)

// Dark theme
val JervisDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBCC7DB),
    onSecondary = Color(0xFF263141),
    secondaryContainer = Color(0xFF3C4758),
    onSecondaryContainer = Color(0xFFD8E3F8),
    tertiary = Color(0xFFDABDE2),
    onTertiary = Color(0xFF3D2846),
    tertiaryContainer = Color(0xFF553F5D),
    onTertiaryContainer = Color(0xFFF7D8FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
)
