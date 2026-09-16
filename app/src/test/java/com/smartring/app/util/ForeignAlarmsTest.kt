package com.smartring.app.util

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The foreign-alarm warning.
 *
 * The wording is tested, not just the logic, because this feature's whole risk is
 * over-promising. A user who reads "SmartRing will disable your other alarm" and believes
 * it will sleep through the alarm they were trying to avoid — which is the exact failure
 * the feature exists to prevent. Android gives no app the ability to cancel another app's
 * alarm, so the text must say so every time it appears.
 */
class ForeignAlarmsTest {

    private val ours = "com.smartring.app"

    private fun at(hour: Int, minute: Int, dayOffset: Int = 0, from: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = from
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // ── Whose alarm is it ───────────────────────────────────────────────────

    @Test
    fun `another app's package is foreign`() =
        assertTrue(ForeignAlarms.isForeignOwner("com.google.android.deskclock", ours))

    /**
     * Our own alarms register through the same device-wide AlarmManager slot, so without
     * this filter the app would warn the user about the alarm they just set here.
     */
    @Test
    fun `our own package is not foreign`() =
        assertFalse(ForeignAlarms.isForeignOwner(ours, ours))

    @Test
    fun `an unreadable owner is not treated as foreign`() {
        // A registration with no showIntent, or one whose creator cannot be read. There
        // is nothing actionable to tell the user about an alarm we cannot even name.
        assertFalse(ForeignAlarms.isForeignOwner(null, ours))
        assertFalse(ForeignAlarms.isForeignOwner("", ours))
        assertFalse(ForeignAlarms.isForeignOwner("   ", ours))
    }

    // ── What the user is told ───────────────────────────────────────────────

    @Test
    fun `no foreign alarm means nothing is said`() =
        assertNull(foreignAlarmWarning(null))

    @Test
    fun `an alarm already in the past is not announced`() {
        // The OS clears a fired registration a moment later; announcing an alarm that has
        // already rung is worse than silence.
        val now = System.currentTimeMillis()
        val stale = ForeignAlarm("com.other.clock", "שעון", now - 60_000)
        assertNull(foreignAlarmWarning(stale, now))
    }

    @Test
    fun `an alarm exactly now is not announced`() {
        val now = System.currentTimeMillis()
        assertNull(foreignAlarmWarning(ForeignAlarm("com.other.clock", "שעון", now), now))
    }

    @Test
    fun `the warning names the app and when it will ring`() {
        val now = at(20, 0, 0, System.currentTimeMillis())
        val tomorrowMorning = at(6, 30, 1, now)
        val warning = foreignAlarmWarning(ForeignAlarm("com.google.android.deskclock", "שעון", tomorrowMorning), now)!!

        assertTrue("must name the app", warning.contains("\"שעון\""))
        assertTrue("must say when", warning.contains("מחר בשעה 06:30"))
    }

    /** The load-bearing sentence. If this disappears the feature becomes a lie. */
    @Test
    fun `the warning always says the other alarm cannot be switched off from here`() {
        val now = System.currentTimeMillis()
        val warning = foreignAlarmWarning(ForeignAlarm("com.other.clock", "שעון", now + 3_600_000), now)!!
        assertTrue(
            "the warning must state that Android does not allow disabling another app's alarm",
            warning.contains("אנדרואיד לא מאפשר לאפליקציה אחת לכבות שעמור של אפליקציה אחרת"),
        )
    }

    @Test
    fun `the warning never claims this app will do anything about it`() {
        val now = System.currentTimeMillis()
        val warning = foreignAlarmWarning(ForeignAlarm("com.other.clock", "שעון", now + 3_600_000), now)!!
        listOf("נכבה", "כיבינו", "השהינו", "ביטלנו").forEach {
            assertFalse("the warning must not promise action: found \"$it\"", warning.contains(it))
        }
    }

    // ── The shared day/time wording ─────────────────────────────────────────

    @Test
    fun `a foreign alarm today is described as today`() {
        val now = at(6, 0, 0, System.currentTimeMillis())
        val later = at(23, 0, 0, now)
        val warning = foreignAlarmWarning(ForeignAlarm("com.other.clock", "שעון", later), now)!!
        assertTrue(warning.contains("היום בשעה 23:00"))
    }

    /** The same wording this app uses for its own next ring, so the two are comparable. */
    @Test
    fun `the day-and-time wording matches the one used for our own alarms`() {
        val now = System.currentTimeMillis()
        val target = at(7, 0, 1, now)
        assertEquals("הצלצול הבא: " + formatDayAndTime(target, now), formatNextFireAt(target, now))
    }
}
