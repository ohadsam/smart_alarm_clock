package com.smartring.app.presentation.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.smartring.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/**
 * Guards the four appwidget-provider descriptors against the gap they shipped with:
 * they declared only targetCellWidth/targetCellHeight, which the platform added in
 * API 31, and no minWidth/minHeight at all. This app's minSdk is 26, so on Android
 * 8–11 every widget declared no size whatsoever for the launcher to lay it out from,
 * and none of them declared the initialLayout that AppWidgetProviderInfo requires.
 *
 * Reads the compiled XML rather than going through AppWidgetManager so the assertions
 * are about what this repo actually ships, and hold without a device or a home-screen
 * host. The matching end-to-end check — that the real framework parses these and hands
 * back a usable size — lives in WidgetProviderInstrumentedTest.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetProviderInfoTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private data class ProviderXml(
        val minWidth: String?,
        val minHeight: String?,
        val targetCellWidth: Int,
        val targetCellHeight: Int,
        val initialLayout: Int,
        val resizeMode: Int,
        val description: Int,
        val updatePeriodMillis: Int,
    )

    private fun parse(xmlRes: Int): ProviderXml {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val parser = ctx.resources.getXml(xmlRes)
        try {
            var event = parser.next()
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "appwidget-provider") {
                    return ProviderXml(
                        minWidth           = parser.getAttributeValue(androidNs, "minWidth"),
                        minHeight          = parser.getAttributeValue(androidNs, "minHeight"),
                        targetCellWidth    = parser.getAttributeIntValue(androidNs, "targetCellWidth", 0),
                        targetCellHeight   = parser.getAttributeIntValue(androidNs, "targetCellHeight", 0),
                        initialLayout      = parser.getAttributeResourceValue(androidNs, "initialLayout", 0),
                        resizeMode         = parser.getAttributeIntValue(androidNs, "resizeMode", 0),
                        description        = parser.getAttributeResourceValue(androidNs, "description", 0),
                        updatePeriodMillis = parser.getAttributeIntValue(androidNs, "updatePeriodMillis", -1),
                    )
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        throw AssertionError("no <appwidget-provider> element in this XML")
    }

    private val allProviders = listOf(
        "small"  to R.xml.widget_small_info,
        "medium" to R.xml.widget_medium_info,
        "wide"   to R.xml.widget_wide_info,
        "large"  to R.xml.widget_large_info,
    )

    @Test
    fun `every widget declares a pre-API-31 minimum size`() {
        allProviders.forEach { (name, res) ->
            val info = parse(res)
            assertNotNull("$name widget must declare android:minWidth for API < 31", info.minWidth)
            assertNotNull("$name widget must declare android:minHeight for API < 31", info.minHeight)
        }
    }

    @Test
    fun `every widget declares an initial layout`() {
        allProviders.forEach { (name, res) ->
            assertEquals(
                "$name widget's initialLayout must be the app's own placeholder layout",
                R.layout.widget_loading, parse(res).initialLayout,
            )
        }
    }

    @Test
    fun `every widget is resizable in both directions and has a picker description`() {
        // resizeMode horizontal|vertical == 0x1 or 0x2.
        val horizontalOrVertical = 3
        allProviders.forEach { (name, res) ->
            val info = parse(res)
            assertEquals("$name widget should be resizable both ways", horizontalOrVertical, info.resizeMode)
            assertNotNull("$name widget needs a picker description", info.description.takeIf { it != 0 })
        }
    }

    @Test
    fun `minimum size matches the declared cell count`() {
        // The platform's documented cell formula: 70 * cells - 30. Pinning both halves
        // together is the point — a hand-edit that changes targetCell* and forgets the
        // matching minWidth/minHeight would otherwise silently re-open the pre-31 gap.
        val expected = mapOf(
            "small"  to Triple(2 to 2, 110, 110),
            "medium" to Triple(4 to 2, 250, 110),
            "wide"   to Triple(4 to 3, 250, 180),
            "large"  to Triple(4 to 4, 250, 250),
        )
        allProviders.forEach { (name, res) ->
            val info = parse(res)
            val (cells, w, h) = expected.getValue(name)
            assertEquals("$name targetCellWidth",  cells.first,  info.targetCellWidth)
            assertEquals("$name targetCellHeight", cells.second, info.targetCellHeight)
            assertEquals("$name minWidth should be 70 * ${cells.first} - 30",
                w, dpOf(info.minWidth))
            assertEquals("$name minHeight should be 70 * ${cells.second} - 30",
                h, dpOf(info.minHeight))
        }
    }

    @Test
    fun `update period is the platform minimum of 30 minutes`() {
        // Anything shorter is silently clamped to 30 minutes by the framework, so a
        // smaller number here would be a lie about how fresh the widget is; the real
        // freshness comes from WidgetRefreshWorker and the embedded TextClock.
        allProviders.forEach { (name, res) ->
            assertEquals("$name updatePeriodMillis", 1_800_000, parse(res).updatePeriodMillis)
        }
    }

    /** "110.0dip" / "110dp" -> 110. The resource compiler's exact spelling for a
     *  dimension attribute read as a raw string isn't contractual, so only the digits
     *  before any decimal point are compared. */
    private fun dpOf(raw: String?): Int =
        requireNotNull(raw) { "dimension attribute missing" }
            .takeWhile { it.isDigit() }
            .toInt()
}
