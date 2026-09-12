package com.kaavalan.note.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * v1.6.3: Obsidian-style typography calibration.
 *
 * Obsidian's design philosophy: system fonts only, document density,
 * tight typographic hierarchy. The default Material 3 scale is a
 * little too loud for a note-taking / instruction-tracker app —
 * titles shout, body is 14sp by default. This scale matches Obsidian
 * (and the wider "minimal note app" tradition):
 *
 *  - bodyLarge    16sp / 24sp   — main content text (1.5 line-height)
 *  - bodyMedium   14sp / 20sp   — secondary content (1.43)
 *  - bodySmall    12sp / 16sp   — metadata, timestamps (1.33)
 *  - titleLarge   22sp / 30sp   — page-level titles (1.36; v2.7 raised from 20/28)
 *  - titleMedium  16sp / 22sp   — card titles (1.4)
 *  - titleSmall   14sp / 20sp   — section labels, "Reading" headers (1.43)
 *  - labelLarge   14sp / 20sp   — primary buttons (1.43)
 *  - labelMedium  12sp / 16sp   — chips, badges (1.33)
 *  - labelSmall   11sp / 16sp   — tags, smallest labels (1.45)
 *  - headlineSmall 24sp / 32sp  — sheet titles (1.33)
 *  - headlineMedium 28sp / 36sp — page titles (1.29)
 *  - displaySmall 28sp / 36sp   — display / number readouts (1.29)
 *
 * Weights: 400 for body, 500 for labels, 600 (semibold) for titles
 * and page headings — never above semibold. The "weight via weight" knob is more readable than
 * "weight via color or size" — Obsidian uses weight sparingly too.
 */
private val DefaultFont = FontFamily.Default

val KaavalanNoteTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = DefaultFont,
        fontWeight = FontWeight.Light,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = DefaultFont, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = DefaultFont, fontWeight = FontWeight.Medium,
        fontSize = 24.sp, lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = DefaultFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = DefaultFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = DefaultFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = DefaultFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = DefaultFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = DefaultFont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = DefaultFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = DefaultFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = DefaultFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)
