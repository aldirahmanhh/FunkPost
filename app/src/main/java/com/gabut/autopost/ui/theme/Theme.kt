package com.gabut.autopost.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The colors Material 3 has no slot for.
 *
 * Material 3 only ships primary/secondary/tertiary/error, so "success" and "warning" used to be
 * borrowed from tertiary/secondary - which painted warnings purple. These roles make state colors
 * explicit and keep them correct in both themes.
 */
@Immutable
data class AutoPostColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color
)

private val LightAutoPostColors = AutoPostColors(
    success = Success,
    successContainer = SuccessGreenContainer,
    onSuccessContainer = OnSuccessGreenContainer,
    warning = Warning,
    warningContainer = WarningContainer,
    onWarningContainer = OnWarningContainer
)

private val DarkAutoPostColors = AutoPostColors(
    success = SuccessDark,
    successContainer = SuccessContainerDark,
    onSuccessContainer = OnSuccessContainerDark,
    warning = WarningDark,
    warningContainer = WarningContainerDark,
    onWarningContainer = OnWarningContainerDark
)

val LocalAutoPostColors = staticCompositionLocalOf { LightAutoPostColors }

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    inversePrimary = PrimaryLight,
    secondary = Secondary,
    onSecondary = Color.White,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = Success,
    onTertiary = Color.White,
    tertiaryContainer = SuccessGreenContainer,
    onTertiaryContainer = OnSuccessGreenContainer,
    error = Danger,
    onError = Color.White,
    errorContainer = DangerContainer,
    onErrorContainer = OnDangerContainer,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceSecondary,
    onSurfaceVariant = Gray,
    surfaceContainer = LightSurfaceSecondary,
    surfaceContainerHigh = LightSurfaceHigh,
    outline = LightBorder,
    outlineVariant = Color(0xFFE7ECF5),
    inverseSurface = DarkSurface,
    inverseOnSurface = DarkTextPrimary
)

private val DarkColors = darkColorScheme(
    primary = PrimaryLight,
    onPrimary = Color(0xFF0B1020),
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    inversePrimary = Primary,
    secondary = SecondaryLight,
    onSecondary = Color(0xFF16032B),
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = SuccessDark,
    onTertiary = Color(0xFF04240F),
    tertiaryContainer = SuccessContainerDark,
    onTertiaryContainer = OnSuccessContainerDark,
    error = DangerLight,
    onError = Color(0xFF3B0710),
    errorContainer = DangerContainerDark,
    onErrorContainer = OnDangerContainerDark,
    background = DarkBg,
    onBackground = DarkTextPrimary,
    surface = DarkCard,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkTextSecondary,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceHigh,
    outline = DarkBorder,
    outlineVariant = DarkSurfaceHigh,
    inverseSurface = Color(0xFFF1F4FA),
    inverseOnSurface = LightTextPrimary
)

@Composable
fun AutoPostTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalAutoPostColors provides if (darkTheme) DarkAutoPostColors else LightAutoPostColors
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AutoPostTypography,
            shapes = AutoPostShapes,
            content = content
        )
    }
}

/** Rounder corners than the Material default, which reads friendlier on a dense list app. */
val AutoPostShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
