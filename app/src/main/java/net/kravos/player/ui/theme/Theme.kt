package net.kravos.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFF7A3D)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1A0E08),
    primaryContainer = Color(0xFF5A2A12),
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFFE0BFAF),
    onSecondary = Color(0xFF2B1A12),
    secondaryContainer = Color(0xFF3D2C24),
    onSecondaryContainer = Color(0xFFFCDCCB),
    tertiary = Color(0xFF9CCFD8),
    background = Color(0xFF111111),
    onBackground = Color(0xFFECE6E3),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFECE6E3),
    surfaceVariant = Color(0xFF2A2523),
    onSurfaceVariant = Color(0xFFC9BFBA),
    surfaceContainer = Color(0xFF1C1A19),
    surfaceContainerHigh = Color(0xFF242120),
    surfaceContainerHighest = Color(0xFF2E2B2A),
    surfaceContainerLow = Color(0xFF181716),
    outline = Color(0xFF8A807B),
    outlineVariant = Color(0xFF3F3936),
    error = Color(0xFFFFB4AB),
)

@Composable
fun KravosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography(), content = content)
}
