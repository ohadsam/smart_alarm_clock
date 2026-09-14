package com.smartring.app.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The app's own colors, measured the same way the widget palette already is.
 *
 * This is not a theoretical concern here: all four accents and onSurfaceVariant are
 * used as literal text and icon colors, not only as container fills — the ring
 * screen's crescendo readout and reminder card, History's status labels, the list
 * card's badges and supporting text, Settings' subtitles. And light mode is a setting
 * the user picks (הגדרות ← עיצוב ← בהיר), so "it looks fine on my phone" proves
 * nothing about half the app's users.
 *
 * What it caught when it was written: light tertiary at 3.13:1 and light error at
 * 4.34:1 on the surface they are drawn on, and dark onSurfaceVariant at 4.21:1 —
 * the same value, and the same mistake, already corrected once in the widget palette
 * two releases earlier and never checked here.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeContrastTest {

    private fun channel(c: Double): Double =
        if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(color: Color): Double {
        val argb = color.toArgb()
        return 0.2126 * channel(((argb shr 16) and 0xFF) / 255.0) +
            0.7152 * channel(((argb shr 8) and 0xFF) / 255.0) +
            0.0722 * channel((argb and 0xFF) / 255.0)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertReadable(scheme: String, role: String, fg: Color, on: String, bg: Color) {
        val ratio = contrast(fg, bg)
        assertTrue(
            "$scheme.$role on $on is %.2f:1, below AA's 4.5:1 for normal text".format(ratio),
            ratio >= 4.5,
        )
    }

    /**
     * Every accent is checked against all three surfaces a screen can put it on.
     * surfaceVariant matters as much as surface: it is the fill Material gives cards
     * and containers, and an accent that only clears contrast on the plain background
     * fails the moment the same label moves inside a card.
     */
    private fun assertAccentsReadable(name: String, c: ColorScheme) {
        val surfaces = listOf("background" to c.background, "surface" to c.surface,
            "surfaceVariant" to c.surfaceVariant)
        val roles = listOf("primary" to c.primary, "secondary" to c.secondary,
            "tertiary" to c.tertiary, "error" to c.error,
            "onSurface" to c.onSurface, "onSurfaceVariant" to c.onSurfaceVariant)
        roles.forEach { (role, fg) ->
            surfaces.forEach { (on, bg) -> assertReadable(name, role, fg, on, bg) }
        }
    }

    @Test
    fun `every dark-scheme accent is readable on every surface it can be drawn on`() {
        assertAccentsReadable("DarkColors", DarkColors)
    }

    @Test
    fun `every light-scheme accent is readable on every surface it can be drawn on`() {
        assertAccentsReadable("LightColors", LightColors)
    }

    @Test
    fun `label text on a filled accent container is readable too`() {
        // The same accents fill buttons, switches and chips, with the matching "on"
        // color as the label — so darkening an accent for text contrast must not push
        // its own label the other way.
        listOf("DarkColors" to DarkColors, "LightColors" to LightColors).forEach { (name, c) ->
            assertReadable(name, "onPrimary", c.onPrimary, "primary", c.primary)
            assertReadable(name, "onSecondary", c.onSecondary, "secondary", c.secondary)
            assertReadable(name, "onTertiary", c.onTertiary, "tertiary", c.tertiary)
            assertReadable(name, "onError", c.onError, "error", c.error)
            assertReadable(name, "onErrorContainer", c.onErrorContainer, "errorContainer", c.errorContainer)
        }
    }

    @Test
    fun `the two schemes really are different`() {
        // A copy-paste that left both pointing at the same scheme would satisfy every
        // assertion above while undoing the entire point of having a light mode.
        assertTrue(DarkColors.background.toArgb() != LightColors.background.toArgb())
        assertTrue(DarkColors.tertiary.toArgb() != LightColors.tertiary.toArgb())
    }

    @Test
    fun `the raw palette constants stay out of the light scheme`() {
        // Blue/Green/Red/Gold are tuned for near-black surfaces — Green is a pale mint
        // at about 1.5:1 on white. They are legitimate on a fixed dark background and
        // nowhere else, so their appearing in the light scheme is always a mistake.
        listOf("Blue" to Blue, "Green" to Green, "Red" to Red, "Gold" to Gold).forEach { (n, raw) ->
            listOf("primary" to LightColors.primary, "secondary" to LightColors.secondary,
                "tertiary" to LightColors.tertiary, "error" to LightColors.error)
                .forEach { (role, used) ->
                    assertTrue("LightColors.$role must not be the dark-tuned $n constant",
                        used.toArgb() != raw.toArgb())
                }
        }
    }
}
