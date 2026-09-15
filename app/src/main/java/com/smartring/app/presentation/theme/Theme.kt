package com.smartring.app.presentation.theme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// private, and deliberately so. These are the dark scheme's raw accent values, and a
// screen reaching for one directly is the single most repeated bug in this app's
// history: Green as text on Light mode's white surface (1.5:1), Gold as the snooze
// button's label there, and most recently White hard-coded on top of Red for the ring
// screen's STOP button (3.14:1) and on primary for the FAB and the weekday circles
// (3.19:1 in dark). Each was found and fixed one call site at a time. File-private is
// the only version of that fix the compiler enforces: screens now have no way to name
// these, and must go through the color roles — which ThemeContrastTest measures.
private val Blue   = Color(0xFF5B8DF6)
private val Green  = Color(0xFF5BF6B0)
private val Red    = Color(0xFFF65B7A)
private val Gold   = Color(0xFFF6C25B)
private val White  = Color(0xFFFFFFFF)

// The four accent constants above are tuned for the dark scheme's near-black surfaces
// and are unreadable as text or icon tints on Light mode's white ones (Green is a pale
// mint, Gold a pale amber — both around 1.5:1 against white). Screens must therefore
// read their accents from these color *roles*, which carry a per-theme value, and use
// the raw constants only where the background is itself a fixed dark color:
//   primary = Blue, tertiary = Green, error = Red, secondary = Gold.
//
// Every value below is measured, not eyeballed: ThemeContrastTest asserts each of these
// roles against every surface it can be drawn on, because all four accents and
// onSurfaceVariant are used as literal *text and icon* colors across the ring screen,
// the alarm list, history and settings — not just as container fills. The numbers that
// forced the current values (all against AA's 4.5:1 for normal text):
//
//   dark  onSurfaceVariant #6E7A96 → 4.21 on surface, 3.76 on surfaceVariant
//   light tertiary         #16A37A → 3.13 on surface, 2.48 on surfaceVariant
//   light error            #D93B5A → 4.34 on surface, 3.44 on surfaceVariant
//   light primary          #3B5FD4 → 4.31 on surfaceVariant
//   light secondary        #8A6200 → 4.25 on surfaceVariant
//
// The light accents are the same hues as the dark ones, darkened — the widget's light
// palette was corrected the same way and to the same values for blue and green, so the
// app and its widgets stay recognisably one design.
// Overriding an accent without its matching `on` color leaves Material's own baseline
// there — and that baseline is a purple family, while this app's accents are blue,
// gold, mint and pink. The result was Material's dark purple #381E72 as the label on a
// blue filled button (4.12:1) and its dark maroon #601410 on the pink error color
// (4.17:1), both under AA, and both chromatically nothing to do with the button they
// sat on. Every accent below therefore names its own `on` color: the scheme's own
// near-black in dark (the accents there are pale), white in light (they are deep).
internal val DarkColors = darkColorScheme(
    primary=Blue, secondary=Gold, tertiary=Green, error=Red,
    onPrimary=Color(0xFF0A0C10), onSecondary=Color(0xFF0A0C10),
    onTertiary=Color(0xFF0A0C10), onError=Color(0xFF0A0C10),
    background=Color(0xFF0A0C10), surface=Color(0xFF13161E),
    surfaceVariant=Color(0xFF1C2030), outline=Color(0xFF252A3A),
    onBackground=Color(0xFFEDF0FA), onSurface=Color(0xFFEDF0FA),
    onSurfaceVariant=Color(0xFF8A94AE),
)
internal val LightColors = lightColorScheme(
    primary=Color(0xFF2F4FB8), secondary=Color(0xFF7A5600),
    tertiary=Color(0xFF0B6047), error=Color(0xFFB3123A),
    onPrimary=White, onSecondary=White, onTertiary=White, onError=White,
)

@Composable
fun SmartRingTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) =
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography  = AppTypography,
        content     = content,
    )
