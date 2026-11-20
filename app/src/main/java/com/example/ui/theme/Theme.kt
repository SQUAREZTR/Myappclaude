package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val ObsidianDarkColorScheme = darkColorScheme(
    primary = PhosphorGreen,
    onPrimary = ObsidianVoid,
    primaryContainer = NvidiaLimeContainer,
    onPrimaryContainer = PhosphorGreen,
    secondary = ClaudeAmber,
    onSecondary = ObsidianVoid,
    secondaryContainer = ClaudeAmberContainer,
    onSecondaryContainer = ClaudeAmber,
    tertiary = TerminalCyan,
    onTertiary = ObsidianVoid,
    tertiaryContainer = TerminalCyanContainer,
    onTertiaryContainer = TerminalCyan,
    background = ObsidianVoid,
    onBackground = TerminalTextPrimary,
    surface = ObsidianSurface,
    onSurface = TerminalTextPrimary,
    surfaceVariant = ObsidianElevated,
    onSurfaceVariant = TerminalTextMuted,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianElevated,
    surfaceContainerHigh = ObsidianCard,
    outline = ObsidianOutline,
    error = TerminalError,
    onError = ObsidianVoid,
    errorContainer = TerminalErrorContainer,
    onErrorContainer = TerminalError
)

private val DaylightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightSurface,
    primaryContainer = LightElevated,
    onPrimaryContainer = LightPrimary,
    secondary = LightSecondary,
    onSecondary = LightSurface,
    tertiary = LightTertiary,
    onTertiary = LightSurface,
    background = LightCanvas,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightElevated,
    onSurfaceVariant = LightTextMuted,
    outline = ObsidianOutline.copy(alpha = 0.25f)
)

val NimTermShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun NimTermTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) ObsidianDarkColorScheme else DaylightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = NimTermShapes,
        content = content
    )
}
