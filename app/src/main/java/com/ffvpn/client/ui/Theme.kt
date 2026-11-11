package com.ffvpn.client.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Firefox orange on near-black, with a violet edge for the connecting state. */
private val Night = darkColorScheme(
    primary = Color(0xFFFF7139),
    onPrimary = Color(0xFF160800),
    primaryContainer = Color(0xFF3A1408),
    onPrimaryContainer = Color(0xFFFFD8C7),
    secondary = Color(0xFF8E7BFF),
    onSecondary = Color(0xFF100A2E),
    tertiary = Color(0xFF4BE0C8),
    background = Color(0xFF0A0B10),
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF14161F),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF1D2029),
    onSurfaceVariant = Color(0xFFA9AEBF),
    outline = Color(0xFF2B2F3C),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF210404)
)

@Composable
fun FirefoxVpnTheme(content: @Composable () -> Unit) {
    // The product is dark by design; a light scheme would lose the orb.
    @Suppress("UNUSED_VARIABLE")
    val light = isSystemInDarkTheme()
    MaterialTheme(colorScheme = Night, content = content)
}
