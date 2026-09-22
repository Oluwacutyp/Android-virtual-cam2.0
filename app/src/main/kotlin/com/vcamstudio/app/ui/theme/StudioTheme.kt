package com.vcamstudio.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Studio palette (blueprint §E): near-black surfaces, cyan accent, amber REC.
val StudioBg = Color(0xFF0E1116)
val StudioSurface = Color(0xFF161B22)
val StudioSurfaceHigh = Color(0xFF1E2630)
val StudioAccent = Color(0xFF22D3EE)
val StudioAmber = Color(0xFFF59E0B)
val StudioRed = Color(0xFFEF4444)
val StudioText = Color(0xFFE6EDF3)
val StudioTextDim = Color(0xFF8B949E)
val StudioBorder = Color(0xFF2A3340)

private val StudioColorScheme = darkColorScheme(
    primary = StudioAccent,
    onPrimary = Color(0xFF06222A),
    secondary = StudioAmber,
    onSecondary = Color(0xFF2A1A00),
    background = StudioBg,
    onBackground = StudioText,
    surface = StudioSurface,
    onSurface = StudioText,
    surfaceVariant = StudioSurfaceHigh,
    onSurfaceVariant = StudioTextDim,
    outline = StudioBorder,
    error = StudioRed,
)

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    // The studio is dark-first by design; system light theme keeps dark surfaces.
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = StudioColorScheme,
        content = content,
    )
}
