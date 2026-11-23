package com.smartring.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every rule here is about input from outside the app: a release on GitHub, a download, a checksum. */
class UpdateMathTest {
    private val base = "https://github.com/ohadsam/smart_alarm_clock/releases"

    @Test
    fun `only this project's release apks may be downloaded`() {
        assertTrue(UpdateMath.validUrl("$base/latest/download/smartring.apk"))
        assertTrue(UpdateMath.validUrl("$base/latest/download/smartring-debug.apk"))
        assertTrue(UpdateMath.validUrl("$base/download/build-77/smartring.apk"))
        assertFalse(UpdateMath.validUrl("$base/latest/download/other.apk"))
        assertFalse(UpdateMath.validUrl("http://github.com/ohadsam/smart_alarm_clock/releases/latest/download/smartring.apk"))
        assertFalse(UpdateMath.validUrl("https://github.com/someone/else/releases/latest/download/smartring.apk"))
        assertFalse(UpdateMath.validUrl("https://evil.example/ohadsam/smart_alarm_clock/releases/latest/download/smartring.apk"))
        assertFalse(UpdateMath.validUrl("$base/download/../../x/smartring.apk"))
        assertFalse(UpdateMath.validUrl("$base/latest/download/smartring.apk?x=1"))
        assertFalse(UpdateMath.validUrl("$base/latest/download/smartring.apk#frag"))
        assertFalse(UpdateMath.validUrl("$base/latest/download/smartring.apk" + "a".repeat(200)))
        assertFalse(UpdateMath.validUrl(null))
    }

    @Test
    fun `the url the app builds is itself one it accepts, for both build types`() {
        assertTrue(UpdateMath.validUrl(UpdateMath.latestUrl(debugBuild = false)))
        assertTrue(UpdateMath.validUrl(UpdateMath.latestUrl(debugBuild = true)))
        assertEquals("smartring-debug.apk", UpdateMath.assetName(true))
        assertEquals("smartring.apk", UpdateMath.assetName(false))
    }

    @Test
    fun `the checksum is the file next to the apk`() {
        assertEquals("$base/latest/download/smartring.apk.sha256", UpdateMath.checksumUrl("$base/latest/download/smartring.apk"))
    }

    @Test
    fun `the hash is read from the checksum file, and only a whole 64-digit hash counts`() {
        val h = "a".repeat(32) + "B".repeat(32)
        assertEquals(h.lowercase(), UpdateMath.parseSha256("$h  smartring.apk\n"))
        assertNull(UpdateMath.parseSha256("not a hash")); assertNull(UpdateMath.parseSha256("abc123")); assertNull(UpdateMath.parseSha256(null))
        assertNull(UpdateMath.parseSha256("f".repeat(63))); assertNull(UpdateMath.parseSha256("f".repeat(65)))
        assertNull(UpdateMath.parseSha256(""))
    }

    @Test
    fun `hex is lower case, two digits a byte`() = assertEquals("00ff10", UpdateMath.toHex(byteArrayOf(0, -1, 16)))

    @Test
    fun `progress is clamped and an unknown size is minus one`() {
        assertEquals(50, UpdateMath.percent(50, 100)); assertEquals(100, UpdateMath.percent(200, 100))
        assertEquals(0, UpdateMath.percent(0, 100)); assertEquals(-1, UpdateMath.percent(5, 0)); assertEquals(-1, UpdateMath.percent(5, -1))
    }

    @Test
    fun `versions compare numerically, not as text`() {
        assertEquals(-1, UpdateMath.compareVersions("1.9.0", "1.10.0"))
        assertEquals(1, UpdateMath.compareVersions("1.15.0", "1.14.9"))
        assertEquals(0, UpdateMath.compareVersions("1.15.0", "1.15.0"))
        assertEquals(-1, UpdateMath.compareVersions("1.15.0", "2.0.0"))
    }

    @Test
    fun `a garbled version never looks newer than the installed one`() {
        assertEquals("garbage reads as 0.0.0, so it is never newer", 1, UpdateMath.compareVersions("1.15.0", "garbage"))
        assertEquals(1, UpdateMath.compareVersions("1.15.0", "1.15"))
        assertEquals(1, UpdateMath.compareVersions("1.15.0", "99999999.0.0"))
    }

    @Test
    fun `the version is read from the release title`() {
        assertEquals("1.15.0", UpdateMath.versionFromTitle("v1.15.0 (build 80)"))
        assertEquals("1.15.0", UpdateMath.versionFromTitle("1.15.0"))
        assertNull(UpdateMath.versionFromTitle("nightly")); assertNull(UpdateMath.versionFromTitle(null)); assertNull(UpdateMath.versionFromTitle(""))
    }

    @Test
    fun `release notes lose control and bidi-override characters and are capped`() {
        val raw = "- ראשון\r\n- שני\u0000\u0007 ‮evil‬\n" + "x".repeat(5000)
        val clean = UpdateMath.cleanNotes(raw, maxChars = 100)
        assertFalse(clean.contains('\u0000')); assertFalse(clean.contains('‮')); assertFalse(clean.contains('\r'))
        assertTrue(clean.startsWith("- ראשון\n- שני"))
        assertTrue(clean.length <= 101)
        assertTrue(clean.endsWith("…"))
        assertEquals("", UpdateMath.cleanNotes(null))
    }
}
