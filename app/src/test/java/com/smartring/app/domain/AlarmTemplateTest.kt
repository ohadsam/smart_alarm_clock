package com.smartring.app.domain

import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmDate
import com.smartring.app.domain.model.AlarmRing
import com.smartring.app.domain.model.AlarmSync
import com.smartring.app.domain.model.LibraryError
import com.smartring.app.domain.model.LibraryResult
import com.smartring.app.domain.model.RecurrenceEnd
import com.smartring.app.domain.model.RecurrenceEndType
import com.smartring.app.domain.model.TemplateLibrary
import com.smartring.app.domain.model.TemplateLimits
import com.smartring.app.domain.model.TemplateOps
import com.smartring.app.domain.model.asTemplateSettings
import com.smartring.app.domain.model.instantiateTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AlarmTemplateTest {

    private val day = 86_400_000L

    /** A fixed "now": midday on 15 June 2026, local time. */
    private val now: Long = Calendar.getInstance().apply { clear(); set(2026, Calendar.JUNE, 15, 12, 0) }.timeInMillis

    private fun utcMidnight(y: Int, m: Int, d: Int) =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m, d) }.timeInMillis

    // ── what a template keeps and what it sheds ──────────────────────────────

    @Test
    fun `a template holds settings, never the alarm's identity or state`() {
        val alarm = Alarm(
            id = 9, name = "x", isEnabled = false, isFrozen = true, occurrencesFired = 4,
            sync = AlarmSync(syncId = "s", isRemoteManaged = true, originDeviceName = "אמא", managedForDeviceId = "c"),
            rings = listOf(AlarmRing(id = 5, alarmId = 9, orderIndex = 3, ringtoneUri = "content://x/1")),
        )

        val t = alarm.asTemplateSettings()

        assertEquals(0L, t.id); assertTrue(t.isEnabled); assertFalse(t.isFrozen); assertEquals(0, t.occurrencesFired)
        assertEquals(AlarmSync.LOCAL, t.sync)
        assertEquals(0L, t.rings[0].id); assertEquals(0L, t.rings[0].alarmId); assertEquals(0, t.rings[0].orderIndex)
        assertEquals("the ringtone is a setting and stays", "content://x/1", t.rings[0].ringtoneUri)
    }

    @Test
    fun `an ordinary template comes back unchanged and without notices`() {
        val alarm = Alarm(name = "בוקר", hour = 6, minute = 45, repeatDaysBitmask = 0b0111110, snoozeEnabled = true, snoozeMinutes = 9)

        val i = instantiateTemplate(alarm, now)

        assertEquals(alarm.asTemplateSettings(), i.alarm)
        assertTrue(i.notices.isEmpty())
    }

    // ── dates that have passed ───────────────────────────────────────────────

    @Test
    fun `a one-off date in the past is dropped, the time of day is kept, and the person is told`() {
        val past = Calendar.getInstance().apply { clear(); set(2026, Calendar.JUNE, 1, 9, 30) }.timeInMillis
        val i = instantiateTemplate(Alarm(name = "טיסה", hour = 7, minute = 0, specificDateTime = past), now)

        assertNull(i.alarm.specificDateTime)
        assertEquals("the stale hour field must not win over the saved date's time", 9, i.alarm.hour)
        assertEquals(30, i.alarm.minute)
        assertEquals(1, i.notices.size)
        assertTrue(i.notices[0].contains("1.6.2026"))
    }

    @Test
    fun `a one-off date in the future is kept as it was`() {
        val future = now + 3 * day
        val i = instantiateTemplate(Alarm(specificDateTime = future), now)
        assertEquals(future, i.alarm.specificDateTime)
        assertTrue(i.notices.isEmpty())
    }

    @Test
    fun `past dates in the list are removed, today and later stay`() {
        val alarm = Alarm(specificDates = listOf(
            AlarmDate(date = utcMidnight(2026, Calendar.JUNE, 10)),
            AlarmDate(date = utcMidnight(2026, Calendar.JUNE, 14)),
            AlarmDate(date = utcMidnight(2026, Calendar.JUNE, 15), label = "היום"),
            AlarmDate(date = utcMidnight(2026, Calendar.JUNE, 20)),
        ))

        val i = instantiateTemplate(alarm, now)

        assertEquals(listOf(utcMidnight(2026, Calendar.JUNE, 15), utcMidnight(2026, Calendar.JUNE, 20)), i.alarm.specificDates.map { it.date })
        assertEquals(1, i.notices.size)
        assertTrue("says how many", i.notices[0].contains("2"))
    }

    @Test
    fun `a single past date is named`() {
        val i = instantiateTemplate(Alarm(specificDates = listOf(AlarmDate(date = utcMidnight(2026, Calendar.JUNE, 3)))), now)
        assertTrue(i.alarm.specificDates.isEmpty())
        assertTrue(i.notices[0].contains("3.6.2026"))
    }

    @Test
    fun `a recurrence end date that has passed is cleared and the notice says the alarm now repeats without end`() {
        val alarm = Alarm(recurrenceEnd = RecurrenceEnd(RecurrenceEndType.UNTIL, now - day, 10))
        val i = instantiateTemplate(alarm, now)

        assertEquals(RecurrenceEndType.FOREVER, i.alarm.recurrenceEnd.type)
        assertNull(i.alarm.recurrenceEnd.untilDate)
        assertTrue(i.notices[0].contains("בלי הגבלה"))
    }

    @Test
    fun `a recurrence end date in the future stays`() {
        val end = RecurrenceEnd(RecurrenceEndType.UNTIL, now + 30 * day, 10)
        assertEquals(end, instantiateTemplate(Alarm(recurrenceEnd = end), now).alarm.recurrenceEnd)
    }

    @Test
    fun `every past date is reported, each in its own notice`() {
        val alarm = Alarm(
            specificDateTime = now - day,
            specificDates = listOf(AlarmDate(date = utcMidnight(2026, Calendar.JUNE, 1))),
            recurrenceEnd = RecurrenceEnd(RecurrenceEndType.UNTIL, now - day, 10),
        )
        assertEquals(3, instantiateTemplate(alarm, now).notices.size)
    }

    // ── the library ──────────────────────────────────────────────────────────

    private fun ok(r: LibraryResult) = (r as LibraryResult.Ok).library
    private fun refused(r: LibraryResult) = (r as LibraryResult.Refused).error

    @Test
    fun `adding trims the name and keeps the settings, not the identity`() {
        val lib = ok(TemplateOps.addTemplate(TemplateLibrary(), "t", "   שגרה   של  בוקר ", null, Alarm(id = 5, isFrozen = true), now))
        val t = lib.template("t")!!
        assertEquals("שגרה של בוקר", t.name)
        assertEquals(0L, t.alarm.id); assertFalse(t.alarm.isFrozen)
    }

    @Test
    fun `a blank name is refused`() {
        assertEquals(LibraryError.BLANK_NAME, refused(TemplateOps.addTemplate(TemplateLibrary(), "t", "  ", null, Alarm(), now)))
        assertEquals(LibraryError.BLANK_NAME, refused(TemplateOps.addFolder(TemplateLibrary(), "f", "", now)))
    }

    @Test
    fun `a template filed in a folder that does not exist goes to the top level`() {
        val lib = ok(TemplateOps.addTemplate(TemplateLibrary(), "t", "x", "ghost", Alarm(), now))
        assertNull(lib.template("t")!!.folderId)
    }

    @Test
    fun `the template and folder limits hold`() {
        var lib = TemplateLibrary()
        repeat(TemplateLimits.MAX_TEMPLATES) { lib = ok(TemplateOps.addTemplate(lib, "t$it", "n$it", null, Alarm(), now)) }
        assertEquals(LibraryError.LIMIT_TEMPLATES, refused(TemplateOps.addTemplate(lib, "x", "n", null, Alarm(), now)))
        assertEquals(LibraryError.LIMIT_TEMPLATES, refused(TemplateOps.duplicateTemplate(lib, "t0", "dup", now)))

        var folders = TemplateLibrary()
        repeat(TemplateLimits.MAX_FOLDERS) { folders = ok(TemplateOps.addFolder(folders, "f$it", "n$it", now)) }
        assertEquals(LibraryError.LIMIT_FOLDERS, refused(TemplateOps.addFolder(folders, "x", "n", now)))
    }

    @Test
    fun `updating replaces the settings and keeps the id, folder and creation time`() {
        var lib = ok(TemplateOps.addFolder(TemplateLibrary(), "f", "תיקייה", 1))
        lib = ok(TemplateOps.addTemplate(lib, "t", "ישן", "f", Alarm(hour = 6), 100))

        lib = ok(TemplateOps.updateTemplate(lib, "t", "חדש", Alarm(hour = 8), 200))

        val t = lib.template("t")!!
        assertEquals("חדש", t.name); assertEquals(8, t.alarm.hour)
        assertEquals("f", t.folderId); assertEquals(100L, t.createdAt); assertEquals(200L, t.updatedAt)
    }

    @Test
    fun `renaming alone leaves the settings`() {
        var lib = ok(TemplateOps.addTemplate(TemplateLibrary(), "t", "ישן", null, Alarm(hour = 6), 1))
        lib = ok(TemplateOps.renameTemplate(lib, "t", "חדש", 2))
        assertEquals(6, lib.template("t")!!.alarm.hour)
        assertEquals(LibraryError.BLANK_NAME, refused(TemplateOps.renameTemplate(lib, "t", " ", 3)))
    }

    @Test
    fun `moving needs the folder to exist`() {
        var lib = ok(TemplateOps.addTemplate(TemplateLibrary(), "t", "x", null, Alarm(), 1))
        lib = ok(TemplateOps.addFolder(lib, "f", "תיקייה", 1))
        lib = ok(TemplateOps.moveTemplate(lib, "t", "f", 2))
        assertEquals("f", lib.template("t")!!.folderId)
        assertEquals(LibraryError.NOT_FOUND, refused(TemplateOps.moveTemplate(lib, "t", "ghost", 3)))
        lib = ok(TemplateOps.moveTemplate(lib, "t", null, 4))
        assertNull(lib.template("t")!!.folderId)
    }

    @Test
    fun `a duplicate is a separate template with a marked name`() {
        var lib = ok(TemplateOps.addTemplate(TemplateLibrary(), "t", "בוקר", null, Alarm(hour = 6), 1))
        lib = ok(TemplateOps.duplicateTemplate(lib, "t", "t2", 5))
        assertEquals(2, lib.templates.size)
        assertEquals("בוקר (עותק)", lib.template("t2")!!.name)
        assertEquals(6, lib.template("t2")!!.alarm.hour)
    }

    @Test
    fun `deleting a folder keeps its templates by default`() {
        var lib = ok(TemplateOps.addFolder(TemplateLibrary(), "f", "תיקייה", 1))
        lib = ok(TemplateOps.addTemplate(lib, "t", "x", "f", Alarm(), 1))

        lib = ok(TemplateOps.deleteFolder(lib, "f"))

        assertTrue(lib.folders.isEmpty())
        assertNull("kept, at the top level", lib.template("t")!!.folderId)
    }

    @Test
    fun `deleting a folder with its contents deletes both`() {
        var lib = ok(TemplateOps.addFolder(TemplateLibrary(), "f", "תיקייה", 1))
        lib = ok(TemplateOps.addTemplate(lib, "t", "x", "f", Alarm(), 1))
        lib = ok(TemplateOps.addTemplate(lib, "u", "y", null, Alarm(), 1))

        lib = ok(TemplateOps.deleteFolder(lib, "f", deleteContents = true))

        assertEquals(listOf("u"), lib.templates.map { it.id })
    }

    @Test
    fun `operations on something that is gone say so`() {
        val lib = TemplateLibrary()
        assertEquals(LibraryError.NOT_FOUND, refused(TemplateOps.deleteTemplate(lib, "x")))
        assertEquals(LibraryError.NOT_FOUND, refused(TemplateOps.updateTemplate(lib, "x", "n", null, 1)))
        assertEquals(LibraryError.NOT_FOUND, refused(TemplateOps.renameFolder(lib, "x", "n")))
        assertEquals(LibraryError.NOT_FOUND, refused(TemplateOps.deleteFolder(lib, "x")))
        assertEquals(LibraryError.NOT_FOUND, refused(TemplateOps.duplicateTemplate(lib, "x", "y", 1)))
    }
}
