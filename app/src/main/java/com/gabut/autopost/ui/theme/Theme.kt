package com.gabut.autopost.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = LightBg,
    primaryContainer = PrimaryDark,
    secondary = Secondary,
    tertiary = Success,
    error = Danger,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightBg,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceSecondary,
    onSurfaceVariant = Gray,
    outline = LightBorder
)

private val DarkColors = darkColorScheme(
    primary = Primary,
    onPrimary = LightBg,
    primaryContainer = PrimaryDark,
    secondary = Secondary,
    tertiary = Success,
    error = Danger,
    background = DarkBg,
    onBackground = LightBg,
    surface = DarkCard,
    onSurface = LightBg,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorder
)

@Composable
fun AutoPostTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AutoPostTypography,
        content = content
    )
}
