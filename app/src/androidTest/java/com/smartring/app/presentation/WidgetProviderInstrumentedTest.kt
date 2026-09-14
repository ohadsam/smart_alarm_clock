package com.smartring.app.presentation

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The end-to-end half of the widget-descriptor coverage: not "does the XML contain
 * these attributes" (WidgetProviderInfoTest, on the JVM) but "does the real framework
 * accept all four providers and hand back a usable AppWidgetProviderInfo". That is
 * what a launcher reads to decide whether the widget can be placed and how big it is,
 * and it is exactly what was broken — the descriptors declared only the API-31
 * targetCell* attributes, so on this emulator's API level the parsed minWidth/minHeight
 * came back as zero.
 */
@RunWith(AndroidJUnit4::class)
class WidgetProviderInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun ourProviders(): List<AppWidgetProviderInfo> =
        AppWidgetManager.getInstance(context).installedProviders
            .filter { it.provider.packageName == context.packageName }

    @Test
    fun allFourWidgetSizesAreInstalled() {
        val names = ourProviders().map { it.provider.className }
        listOf("Small", "Medium", "Wide", "Large").forEach { size ->
            assertTrue(
                "the $size widget receiver should be registered with AppWidgetManager, got $names",
                names.any { it.endsWith("SmartRingWidget${size}Receiver") },
            )
        }
    }

    @Test
    fun everyWidgetReportsANonZeroMinimumSize() {
        val providers = ourProviders()
        assertEquals("expected exactly four widget providers", 4, providers.size)
        providers.forEach { info ->
            assertTrue(
                "${info.provider.className} reports minWidth=${info.minWidth}; a launcher on " +
                    "an API < 31 device has nothing to size it from",
                info.minWidth > 0,
            )
            assertTrue(
                "${info.provider.className} reports minHeight=${info.minHeight}",
                info.minHeight > 0,
            )
        }
    }

    @Test
    fun everyWidgetHasAnInitialLayoutAndIsResizable() {
        ourProviders().forEach { info ->
            assertTrue(
                "${info.provider.className} has no initialLayout — a freshly placed widget " +
                    "is a blank hole until Glance's first render lands",
                info.initialLayout != 0,
            )
            assertEquals(
                "${info.provider.className} should be resizable in both directions",
                AppWidgetProviderInfo.RESIZE_HORIZONTAL or AppWidgetProviderInfo.RESIZE_VERTICAL,
                info.resizeMode,
            )
        }
    }
}
