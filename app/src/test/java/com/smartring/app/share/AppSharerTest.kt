package com.smartring.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class AppSharerTest {

    @Test
    fun `the message carries the version and says an update goes over the top`() {
        val m = AppSharer.message("1.14.0")
        assertTrue(m.contains("1.14.0"))
        assertTrue(m.contains("אין צורך להסיר"))
        assertTrue(m.contains("ממקורות לא ידועים"))
    }

    @Test
    fun `the file name carries the build number so two builds of a version differ`() {
        assertEquals("smartring-1.14.0.apk", AppSharer.fileName("1.14.0", false))
        assertEquals("smartring-1.14.0.apk.zip", AppSharer.fileName("1.14.0", true))
        assertEquals("smartring-1.14.0-b38.apk", AppSharer.fileName("1.14.0", false, 38))
        assertEquals("smartring-1.14.0-b38.apk.zip", AppSharer.fileName("1.14.0", true, 38))
    }

    @Test
    fun `the zip holds the apk bytes unchanged`() {
        val dir = File.createTempFile("share", "").apply { delete(); mkdirs() }
        try {
            val apk = File(dir, "a.apk").apply { writeBytes(ByteArray(5000) { (it % 251).toByte() }) }
            val zip = File(dir, "a.zip")
            AppSharer.zipSingle(apk, zip)
            ZipFile(zip).use { z ->
                assertEquals(apk.readBytes().toList(), z.getInputStream(z.getEntry("a.apk")).readBytes().toList())
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
