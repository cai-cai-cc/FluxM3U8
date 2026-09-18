package com.flux.m3u8.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF0A0B10),
    primaryContainer = Color(0xFF2A3168),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Cyan,
    onSecondary = Color(0xFF062B31),
    secondaryContainer = Color(0xFF0E3B44),
    onSecondaryContainer = Color(0xFFB8EDF7),
    background = BgDark,
    onBackground = Color(0xFFE7E9F0),
    surface = SurfaceDark,
    onSurface = Color(0xFFE7E9F0),
    surfaceVariant = SurfaceDarkHigh,
    onSurfaceVariant = Color(0xFFA9B0C4),
    error = Danger,
    onError = Color(0xFF2A0A0A),
    outline = Color(0xFF2A2E3C),
    outlineVariant = Color(0xFF1E2230)
)

private val LightColorScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E6FF),
    onPrimaryContainer = Color(0xFF1B2370),
    secondary = Color(0xFF0091A8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDF2F8),
    onSecondaryContainer = Color(0xFF003A43),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF141824),
    surface = Color.White,
    onSurface = Color(0xFF141824),
    surfaceVariant = Color(0xFFEFF1F7),
    onSurfaceVariant = Color(0xFF5A6175),
    error = Color(0xFFDC2626),
    onError = Color.White,
    outline = Color(0xFFD7DBE6),
    outlineVariant = Color(0xFFE7EAF2)
)

@Composable
fun FluxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    lightPreference: Boolean? = null,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val useDark = lightPreference?.let { !it } ?: darkTheme

    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (useDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        useDark -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
        content = content
    )
}
