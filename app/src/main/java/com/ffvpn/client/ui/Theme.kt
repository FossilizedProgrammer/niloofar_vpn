package com.ffvpn.client.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.ffvpn.client.core.Prefs
import com.ffvpn.client.core.rtl

/**
 * Lotus: jade over deep indigo, with orchid and gold as the two quieter voices.
 * The palette is dark by design, because the orb and its halo are the screen.
 */
private val LotusNight = darkColorScheme(
    primary = Color(0xFF6FE7C4),
    onPrimary = Color(0xFF03251C),
    primaryContainer = Color(0xFF0E3B31),
    onPrimaryContainer = Color(0xFFB8FFE9),
    secondary = Color(0xFFC7A8FF),
    onSecondary = Color(0xFF1B0B33),
    secondaryContainer = Color(0xFF2A1B4A),
    onSecondaryContainer = Color(0xFFEDE1FF),
    tertiary = Color(0xFFFFCB8B),
    onTertiary = Color(0xFF2B1800),
    background = Color(0xFF06080E),
    onBackground = Color(0xFFEDF1F7),
    surface = Color(0xFF0F141C),
    onSurface = Color(0xFFEDF1F7),
    surfaceVariant = Color(0xFF171D28),
    onSurfaceVariant = Color(0xFF9AA6B9),
    outline = Color(0xFF25303F),
    error = Color(0xFFFF7A8A),
    onError = Color(0xFF2A0509)
)

/** Shared gradients so every screen sits on the same sky. */
object Lotus {
    val background = Brush.verticalGradient(
        listOf(Color(0xFF0A1220), Color(0xFF06080E), Color(0xFF0C0A18))
    )
    val idle = Color(0xFF8A93A6)
    val halo = Brush.radialGradient(
        listOf(Color(0xFF6FE7C4).copy(alpha = 0.10f), Color.Transparent)
    )
}

@Composable
fun NiloofarTheme(content: @Composable () -> Unit) {
    // A trailing "…" is a bidirectional neutral: it takes the paragraph's
    // direction, so an LTR paragraph puts it at the head of a Persian sentence
    // instead of its tail. The direction is pinned here rather than left to the
    // Activity configuration, which the text does not always inherit.
    val direction = if (Prefs.language.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        MaterialTheme(colorScheme = LotusNight) {
            ProvideAppTextToolbar(content)
        }
    }
}
