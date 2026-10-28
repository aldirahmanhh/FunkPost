package com.gabut.autopost.ui.theme

import androidx.compose.ui.graphics.Color

// Brand palette mirrored from autopost.py:541-572. The extra roles below exist so the
// UI can express state (running, warning, danger) without hard-coding colors in composables.
val Primary = Color(0xFF4361EE)
val PrimaryDark = Color(0xFF3150D4)
val PrimaryLight = Color(0xFF7B93FF)
val PrimaryContainerLight = Color(0xFFE0E6FF)
val OnPrimaryContainerLight = Color(0xFF12246B)
val PrimaryContainerDark = Color(0xFF22326B)
val OnPrimaryContainerDark = Color(0xFFDDE4FF)

val Secondary = Color(0xFF7209B7)
val SecondaryLight = Color(0xFF9D4EDD)
val SecondaryContainerLight = Color(0xFFEADCFA)
val OnSecondaryContainerLight = Color(0xFF2E1065)
val SecondaryContainerDark = Color(0xFF3B1D63)
val OnSecondaryContainerDark = Color(0xFFEEDCFF)

// Solid "live" green used for dots and icons. The container/content pair below is what
// actually carries text, because a bright green fails contrast on a pale chip.
val Success = Color(0xFF16A34A)
val SuccessDark = Color(0xFF4ADE80)
val SuccessGreen = Color(0xFF22C55E)
val SuccessGreenContainer = Color(0xFFDCFCE7)
val OnSuccessGreenContainer = Color(0xFF14532D)
val SuccessContainerDark = Color(0xFF10301F)
val OnSuccessContainerDark = Color(0xFF86EFAC)

// Amber, deliberately not the brand purple: a warning must read as a warning.
val Warning = Color(0xFFD97706)
val WarningDark = Color(0xFFFBBF24)
val WarningContainer = Color(0xFFFEF3C7)
val OnWarningContainer = Color(0xFF92400E)
val WarningContainerDark = Color(0xFF3A2A0E)
val OnWarningContainerDark = Color(0xFFFCD34D)

val Danger = Color(0xFFE11D48)
val DangerLight = Color(0xFFFF8A94)
val DangerContainer = Color(0xFFFEE2E2)
val OnDangerContainer = Color(0xFF7F1D1D)
val DangerContainerDark = Color(0xFF3A1618)
val OnDangerContainerDark = Color(0xFFFFB4BC)

// Dark surfaces - layered so cards sit on the background with real depth.
val DarkBg = Color(0xFF0B0F1A)
val DarkCard = Color(0xFF141A29)
val DarkSurface = Color(0xFF1B2234)
val DarkSurfaceHigh = Color(0xFF232C42)
val DarkBorder = Color(0xFF2B3550)
val DarkTextPrimary = Color(0xFFF1F4FA)
val DarkTextSecondary = Color(0xFF9AA6BE)

// Light surfaces.
val Light = Color(0xFFF8F9FA)
val LightBg = Color(0xFFF4F6FB)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceSecondary = Color(0xFFEDF1F8)
val LightSurfaceHigh = Color(0xFFDFE6F1)
val Gray = Color(0xFF5B6678)
val LightBorder = Color(0xFFDCE2EE)
val LightTextPrimary = Color(0xFF151C2C)
