package io.github.sc00tz.ringbridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The web dashboard's tokens (web/app.css), so phone and browser look the same. Series colors were checked for color-blind separation. */
data class Palette(
    val s1: Color, val s2: Color, val s3: Color, val s4: Color, val s5: Color,
    val good: Color, val best: Color, val warning: Color, val critical: Color, val surface2: Color,
)

private val LightPalette = Palette(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100), Color(0xFFE87BA4),
    Color(0xFF0CA30C), Color(0xFF006300), Color(0xFFFAB219), Color(0xFFD03B3B), Color(0xFFF0EFEC))
private val DarkPalette = Palette(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500), Color(0xFFD55181),
    Color(0xFF0CA30C), Color(0xFF5FD35F), Color(0xFFFAB219), Color(0xFFD03B3B), Color(0xFF2C2C2A))

val LocalPalette = staticCompositionLocalOf { LightPalette }

private val Light = lightColorScheme(
    primary = Color(0xFF256ABF), onPrimary = Color.White, primaryContainer = Color(0xFFCDE2FB), onPrimaryContainer = Color(0xFF104281),
    secondaryContainer = Color(0xFFCDE2FB), onSecondaryContainer = Color(0xFF104281),
    background = Color(0xFFF9F9F7), onBackground = Color(0xFF0B0B0B), surface = Color(0xFFFCFCFB), onSurface = Color(0xFF0B0B0B),
    surfaceVariant = Color(0xFFF0EFEC), onSurfaceVariant = Color(0xFF52514E), outlineVariant = Color(0xFFE1E0D9), outline = Color(0xFFC3C2B7),
    surfaceContainer = Color(0xFFFCFCFB), error = Color(0xFFD03B3B),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF86B6EF), onPrimary = Color(0xFF0D1B2E), primaryContainer = Color(0xFF184F95), onPrimaryContainer = Color(0xFFCDE2FB),
    secondaryContainer = Color(0xFF184F95), onSecondaryContainer = Color(0xFFCDE2FB),
    background = Color(0xFF0D0D0D), onBackground = Color(0xFFF0EFEC), surface = Color(0xFF1A1A19), onSurface = Color(0xFFF0EFEC),
    surfaceVariant = Color(0xFF2C2C2A), onSurfaceVariant = Color(0xFFC3C2B7), outlineVariant = Color(0xFF2C2C2A), outline = Color(0xFF383835),
    surfaceContainer = Color(0xFF1A1A19), error = Color(0xFFD03B3B),
)

@Composable
fun RingBridgeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}
