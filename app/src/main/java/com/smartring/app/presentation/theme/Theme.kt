package com.smartring.app.presentation.theme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Blue   = Color(0xFF5B8DF6)
val Green  = Color(0xFF5BF6B0)
val Red    = Color(0xFFF65B7A)
val Gold   = Color(0xFFF6C25B)
val White  = Color(0xFFFFFFFF)

private val DarkColors = darkColorScheme(
    primary=Blue, tertiary=Green, error=Red,
    background=Color(0xFF0A0C10), surface=Color(0xFF13161E),
    surfaceVariant=Color(0xFF1C2030), outline=Color(0xFF252A3A),
    onBackground=Color(0xFFEDF0FA), onSurface=Color(0xFFEDF0FA),
    onSurfaceVariant=Color(0xFF6E7A96),
)
private val LightColors = lightColorScheme(
    primary=Color(0xFF3B5FD4), tertiary=Color(0xFF16A37A), error=Color(0xFFD93B5A),
)

@Composable
fun SmartRingTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) =
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography  = AppTypography,
        content     = content,
    )
