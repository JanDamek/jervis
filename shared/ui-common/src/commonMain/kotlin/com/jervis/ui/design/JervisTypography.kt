package com.jervis.ui.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Jervis typography configuration.
 *
 * Uses Material 3 type scale. Compact devices get slightly smaller text
 * for better information density on phone screens.
 */
object JervisTypography {

    fun create(isCompact: Boolean = false): Typography {
        val scale = if (isCompact) 0.92f else 1.0f
        return Typography(
            displayLarge = TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = (57 * scale).sp,
                lineHeight = (64 * scale).sp,
                letterSpacing = (-0.25).sp,
            ),
            displayMedium = TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = (45 * scale).sp,
                lineHeight = (52 * scale).sp,
            ),
            displaySmall = TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = (36 * scale).sp,
                lineHeight = (44 * scale).sp,
            ),
            headlineLarge = TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = (32 * scale).sp,
                lineHeight = (40 * scale).sp,
            ),
            headlineMedium = TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = (28 * scale).sp,
                lineHeight = (36 * scale).sp,
            ),
            headlineSmall = TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = (24 * scale).sp,
                lineHeight = (32 * scale).sp,
            ),
            titleLarge = TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = (22 * scale).sp,
                lineHeight = (28 * scale).sp,
            ),
            titleMedium = TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = (16 * scale).sp,
                lineHeight = (24 * scale).sp,
                letterSpacing = 0.15.sp,
            ),
            titleSmall = TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = (14 * scale).sp,
                lineHeight = (20 * scale).sp,
                letterSpacing = 0.1.sp,
            ),
            bodyLarge = TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = (16 * scale).sp,
                lineHeight = (24 * scale).sp,
                letterSpacing = 0.5.sp,
            ),
            bodyMedium = TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = (14 * scale).sp,
                lineHeight = (20 * scale).sp,
                letterSpacing = 0.25.sp,
            ),
            bodySmall = TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = (12 * scale).sp,
                lineHeight = (16 * scale).sp,
                letterSpacing = 0.4.sp,
            ),
            labelLarge = TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = (14 * scale).sp,
                lineHeight = (20 * scale).sp,
                letterSpacing = 0.1.sp,
            ),
            labelMedium = TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = (12 * scale).sp,
                lineHeight = (16 * scale).sp,
                letterSpacing = 0.5.sp,
            ),
            labelSmall = TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = (11 * scale).sp,
                lineHeight = (16 * scale).sp,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}
