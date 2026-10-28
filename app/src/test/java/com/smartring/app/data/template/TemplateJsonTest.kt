package com.smartring.app.data.template

import androidx.test.core.app.ApplicationProvider
import com.smartring.app.data.repository.TemplateRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmDate
import com.smartring.app.domain.model.AlarmRing
import com.smartring.app.domain.model.AlarmTemplate
import com.smartring.app.domain.model.LibraryResult
import com.smartring.app.domain.model.RecurrenceEnd
import com.smartring.app.domain.model.RecurrenceEndType
import com.smartring.app.domain.model.RepeatFrequency
import com.smartring.app.domain.model.TemplateFolder
import com.smartring.app.domain.model.TemplateLibrary
import com.smartring.app.domain.model.VibrationMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric because `org.json` is a stub on a plain JVM; here it is the real thing. */
@RunWith(RobolectricTestRunner::class)
class TemplateJsonTest {

    private val full = Alarm(
        name = "קום", hour = 6, minute = 5, specificDateTime = 1_900_000_000_000L,
        repeatDaysBitmask = 0b0101010, repeatFrequency = RepeatFrequency.BIWEEKLY,
        recurrenceEnd = RecurrenceEnd(RecurrenceEndType.UNTIL, 1_950_000_000_000L, 12),
        specificDates = listOf(AlarmDate(date = 1_900_000_000_000L, label = "חג"), AlarmDate(date = 1_910_000_000_000L)),
        ringDurationSeconds = 90,
        rings = listOf(
            AlarmRing(orderIndex = 0, durationSeconds = 30, volumePercent = 70, ringtoneUri = "content://media/9", delayAfterSeconds = 5),
            AlarmRing(orderIndex = 1, durationSeconds = 45, volumePercent = 100, ringtoneUri = "default", delayAfterSeconds = 0),
        ),
        snoozeEnabled = true, snoozeMinutes = 8, snoozeMaxCount = 4, isShabbatMode = true, reminderText = "תזכורת",
        vibrationMode = VibrationMode.VIBRATION_THEN_SOUND, vibrationOnlySeconds = 12,
        crescendoEnabled = true, crescendoStartVolume = 20, crescendoStepSeconds = 10, crescendoStepPercent = 15,
    )

    private fun lib(vararg alarms: Alarm) = TemplateLibrary(
        folders = listOf(TemplateFolder("f1", "עבודה", 5)),
        templates = alarms.mapIndexed { i, a -> AlarmTemplate("t$i", "תבנית $i", if (i == 0) "f1" else null, a, 10, 20) },
    )

    @Test
    fun `every setting survives the round trip`() {
        val back = TemplateJson.decode(TemplateJson.encode(lib(full)))
        assertEquals(full, back.templates.single().alarm)
        assertEquals("f1", back.templates.single().folderId)
        assertEquals(TemplateFolder("f1", "עבודה", 5), back.folders.single())
        assertEquals(10L, back.templates.single().createdAt)
        assertEquals(20L, back.templates.single().updatedAt)
    }

    @Test
    fun `an alarm with nothing optional set round-trips too`() {
        val plain = Alarm(name = "x")
        assertEquals(plain, TemplateJson.decode(TemplateJson.encode(lib(plain))).templates.single().alarm)
    }

    @Test
    fun `nothing, blank and garbage all read as an empty library`() {
        for (raw in listOf(null, "", "   ", "not json", "[1,2]", "{")) {
            val l = TemplateJson.decode(raw)
            assertTrue(raw.toString(), l.templates.isEmpty() && l.folders.isEmpty())
        }
    }

    @Test
    fun `one bad entry is dropped and the rest are kept`() {
        val good = TemplateJson.encode(lib(full, Alarm(name = "שני")))
        // Break the first template: remove its alarm object.
        val broken = org.json.JSONObject(good).also {
            it.getJSONArray("templates").getJSONObject(0).remove("alarm")
        }.toString()

        val back = TemplateJson.decode(broken)

        assertEquals(listOf("t1"), back.templates.map { it.id })
    }

    @Test
    fun `unknown enum names take the default instead of failing`() {
        val doc = org.json.JSONObject(TemplateJson.encode(lib(full))).also {
            val a = it.getJSONArray("templates").getJSONObject(0).getJSONObject("alarm")
            a.put("repeatFrequency", "FORTNIGHTLY"); a.put("vibrationMode", "BUZZ")
            a.getJSONObject("recurrenceEnd").put("type", "SOMETIME")
        }.toString()

        val a = TemplateJson.decode(doc).templates.single().alarm

        assertEquals(RepeatFrequency.WEEKLY, a.repeatFrequency)
        assertEquals(VibrationMode.SOUND_AND_VIBRATION, a.vibrationMode)
        assertEquals(RecurrenceEndType.FOREVER, a.recurrenceEnd.type)
    }

    @Test
    fun `values from a damaged or newer file are pulled back into range`() {
        val doc = org.json.JSONObject(TemplateJson.encode(lib(full))).also {
            val a = it.getJSONArray("templates").getJSONObject(0).getJSONObject("alarm")
            a.put("hour", 99); a.put("minute", -4); a.put("snoozeMinutes", 100000); a.put("repeatDaysBitmask", 0xFFFF)
        }.toString()

        val a = TemplateJson.decode(doc).templates.single().alarm

        assertEquals(23, a.hour); assertEquals(0, a.minute); assertEquals(120, a.snoozeMinutes); assertEquals(0x7F, a.repeatDaysBitmask)
    }

    @Test
    fun `a template pointing at a missing folder is filed at the top`() {
        val doc = org.json.JSONObject(TemplateJson.encode(lib(full))).also {
            it.getJSONArray("templates").getJSONObject(0).put("folderId", "gone")
        }.toString()
        assertNull(TemplateJson.decode(doc).templates.single().folderId)
    }

    @Test
    fun `duplicate ids keep the first`() {
        val doc = org.json.JSONObject(TemplateJson.encode(lib(full, Alarm(name = "b")))).also {
            it.getJSONArray("templates").getJSONObject(1).put("id", "t0")
        }.toString()
        assertEquals(1, TemplateJson.decode(doc).templates.size)
    }

    // ── the repository over a real DataStore ────────────────────────────────

    @Test
    fun `the repository persists what was saved and applies the rules`() = runBlocking {
        val repo = TemplateRepository(ApplicationProvider.getApplicationContext())
        // The DataStore file is shared by every test in this process.
        repo.current().templates.forEach { repo.delete(it.id) }
        repo.current().folders.forEach { repo.deleteFolder(it.id, true) }

        val folder = (repo.addFolder("בית") as LibraryResult.Ok).id!!
        val id = (repo.saveNew("ראשונה", folder, full) as LibraryResult.Ok).id!!

        val lib = repo.current()
        assertEquals("ראשונה", lib.template(id)!!.name)
        assertEquals(folder, lib.template(id)!!.folderId)
        assertEquals(full.copy(id = 0), lib.template(id)!!.alarm.copy(id = 0))

        repo.deleteFolder(folder, deleteContents = false)
        assertNull(repo.current().template(id)!!.folderId)

        repo.delete(id)
        assertTrue(repo.current().templates.isEmpty())
    }
}
