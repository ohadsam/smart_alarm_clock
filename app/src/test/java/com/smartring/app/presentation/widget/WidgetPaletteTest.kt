package com.smartring.app.presentation.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.smartring.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The widgets' colors are defined twice and have to agree: once in Kotlin
 * ([DarkWidgetPalette]/[LightWidgetPalette]), which colors the Glance content and the
 * embedded TextClock/Chronometer, and once as XML colors, which the rounded shape
 * drawables need because Glance's cornerRadius() only rounds on API 31+ and a
 * drawable is the only way to round below it.
 *
 * Two definitions of one color is exactly the setup that drifts, and the failure is
 * ugly and silent: a widget whose frame and body come from opposite themes — a
 * near-black slab with a pale border on a light home screen. So both sides are
 * asserted equal here, under each night-mode qualifier separately, because that is
 * also the only way to check that the -night resources are wired up at all.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetPaletteTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun colorRes(id: Int): Int = ContextCompat.getColor(ctx, id)

    private fun assertMatchesXml(palette: WidgetPalette) {
        assertEquals("widget_background", palette.background.toArgb(), colorRes(R.color.widget_background))
        assertEquals("widget_frame_border", palette.frameBorder.toArgb(), colorRes(R.color.widget_frame_border))
        assertEquals("widget_row_background", palette.rowBackground.toArgb(), colorRes(R.color.widget_row_background))
    }

    // ── paletteFor() follows the device's night mode ─────────────────────────

    @Test
    @Config(qualifiers = "+night")
    fun `night mode renders the dark palette, and its XML twin matches`() {
        assertTrue(isNightMode(ctx))
        assertEquals(DarkWidgetPalette, paletteFor(ctx))
        assertMatchesXml(DarkWidgetPalette)
    }

    @Test
    @Config(qualifiers = "+notnight")
    fun `day mode renders the light palette, and its XML twin matches`() {
        assertTrue(!isNightMode(ctx))
        assertEquals(LightWidgetPalette, paletteFor(ctx))
        assertMatchesXml(LightWidgetPalette)
    }

    @Test
    @Config(qualifiers = "+notnight")
    fun `the loading placeholder is not left on the dark theme's colors in day mode`() {
        // The initialLayout is what the host draws before Glance's first render. It
        // is plain XML, so it follows -night on its own — but only if a day variant
        // exists at all, which is the part worth pinning.
        assertEquals(LightWidgetPalette.background.toArgb(), colorRes(R.color.widget_loading_bg))
        assertEquals(LightWidgetPalette.textSecondary.toArgb(), colorRes(R.color.widget_loading_text))
    }

    @Test
    @Config(qualifiers = "+night")
    fun `the loading placeholder follows night mode too`() {
        assertEquals(DarkWidgetPalette.background.toArgb(), colorRes(R.color.widget_loading_bg))
        assertEquals(DarkWidgetPalette.textSecondary.toArgb(), colorRes(R.color.widget_loading_text))
    }

    @Test
    fun `the two palettes are genuinely different`() {
        // A copy-paste that left both palettes identical would satisfy every other
        // assertion here while undoing the whole point of having two.
        assertNotEquals(DarkWidgetPalette, LightWidgetPalette)
    }

    // ── Legibility ───────────────────────────────────────────────────────────

    private fun channel(c: Double): Double =
        if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun relativeLuminance(color: Color): Double {
        val argb = color.toArgb()
        val r = channel(((argb shr 16) and 0xFF) / 255.0)
        val g = channel(((argb shr 8) and 0xFF) / 255.0)
        val b = channel((argb and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** [foreground] over [background], both treated as opaque. */
    private fun contrast(foreground: Color, background: Color): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /** Composites a translucent color over an opaque one, the way the row pill sits
     *  on the widget body. */
    private fun over(top: Color, bottom: Color): Color {
        val alpha = top.alpha
        return Color(
            red   = top.red * alpha + bottom.red * (1 - alpha),
            green = top.green * alpha + bottom.green * (1 - alpha),
            blue  = top.blue * alpha + bottom.blue * (1 - alpha),
        )
    }

    private fun assertLegible(name: String, palette: WidgetPalette) {
        // The widgets' type runs 9–11sp — "normal text" for contrast purposes, well
        // under the 18pt/14pt-bold large-text exemption — and it is read half-awake in
        // a dark room, so AA's 4.5:1 is the floor, not a stretch goal.
        val rowSurface = over(palette.rowBackground, palette.background)
        listOf(
            "textPrimary"   to palette.textPrimary,
            "textSecondary" to palette.textSecondary,
            "accentBlue"    to palette.accentBlue,
            "accentGreen"   to palette.accentGreen,
        ).forEach { (label, color) ->
            val onBody = contrast(color, palette.background)
            assertTrue("$name.$label on the widget body is $onBody:1, below AA 4.5:1", onBody >= 4.5)
            val onRow = contrast(color, rowSurface)
            assertTrue("$name.$label on an alarm row is $onRow:1, below AA 4.5:1", onRow >= 4.5)
        }
    }

    @Test
    fun `every dark-palette foreground clears WCAG AA on the body and on a row`() {
        assertLegible("DarkWidgetPalette", DarkWidgetPalette)
    }

    @Test
    fun `every light-palette foreground clears WCAG AA on the body and on a row`() {
        assertLegible("LightWidgetPalette", LightWidgetPalette)
    }

    @Test
    fun `widget backgrounds are opaque`() {
        // They used to carry an 0xEE/0xF2 alpha, which let the wallpaper through and
        // cost up to 1.5 stops of contrast — on a bright wallpaper the dark widget's
        // row text fell to 3.5:1. Every ratio asserted above is computed against an
        // opaque body, so that assumption has to be enforced, not assumed.
        assertEquals(1f, DarkWidgetPalette.background.alpha, 0f)
        assertEquals(1f, LightWidgetPalette.background.alpha, 0f)
    }
}
