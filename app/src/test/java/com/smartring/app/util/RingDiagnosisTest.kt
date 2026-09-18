package com.smartring.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "why didn't my alarm ring" verdicts.
 *
 * Tested because this screen's whole value is being *right*. A diagnosis that reports
 * everything fine while an alarm is silently un-armed is worse than no screen at all: it
 * converts a user who would have kept looking into one who stops.
 */
class RingDiagnosisTest {

    private val healthy = DiagnosisInputs(
        hasAnyAlarm = true,
        hasArmedAlarm = true,
        nextArmedAtMillis = System.currentTimeMillis() + 3_600_000,
        systemNextAlarmIsOurs = true,
        systemHasNextAlarm = true,
        canScheduleExactAlarms = true,
        ignoringBatteryOptimizations = true,
        notificationsGranted = true,
        canUseFullScreenIntent = true,
        alarmVolumeAudible = true,
    )

    private fun severities(inputs: DiagnosisInputs) = diagnoseRinging(inputs).map { it.severity }

    private fun itemTitled(inputs: DiagnosisInputs, title: String) =
        diagnoseRinging(inputs).first { it.title == title }

    // ── The healthy case ────────────────────────────────────────────────────

    @Test
    fun `a healthy setup reports no problems at all`() {
        assertTrue(severities(healthy).all { it == DiagnosisSeverity.OK })
        assertEquals("הכול תקין — השעמור אמור לצלצל כמתוכנן.", diagnosisHeadline(diagnoseRinging(healthy)))
    }

    @Test
    fun `every input produces a row, so nothing is silently skipped`() {
        // Seven checks: alarms exist, system registration, exact alarms, battery, volume,
        // notifications, full-screen. A row disappearing would be a check nobody runs.
        assertEquals(7, diagnoseRinging(healthy).size)
    }

    // ── Blockers ────────────────────────────────────────────────────────────

    @Test
    fun `no alarms at all is a blocker`() =
        assertEquals(
            DiagnosisSeverity.BLOCKER,
            itemTitled(healthy.copy(hasAnyAlarm = false, hasArmedAlarm = false), "אין שעמורים").severity,
        )

    /** The single most common real cause, and the one the app kept failing to explain. */
    @Test
    fun `alarms that exist but are all off is a blocker that names the auto-switch-off`() {
        val item = itemTitled(healthy.copy(hasArmedAlarm = false), "אין שעמור פעיל")
        assertEquals(DiagnosisSeverity.BLOCKER, item.severity)
        assertTrue("must explain why a one-time alarm switched itself off",
            item.detail.contains("חד-פעמי נכבה אוטומטית"))
    }

    @Test
    fun `battery optimisation is a blocker and offers the fix`() {
        val item = itemTitled(healthy.copy(ignoringBatteryOptimizations = false), "אופטימיזציית סוללה")
        assertEquals(DiagnosisSeverity.BLOCKER, item.severity)
        assertEquals(DiagnosisFix.BATTERY, item.fix)
    }

    @Test
    fun `a muted alarm stream is a blocker, not a warning`() {
        // The alarm runs perfectly and nobody hears it. That is a failure to ring.
        val item = itemTitled(healthy.copy(alarmVolumeAudible = false), "עוצמת ערוץ השעמור")
        assertEquals(DiagnosisSeverity.BLOCKER, item.severity)
        assertEquals(DiagnosisFix.VOLUME, item.fix)
    }

    @Test
    fun `missing exact-alarm permission is a blocker and offers the fix`() {
        val item = itemTitled(healthy.copy(canScheduleExactAlarms = false), "הרשאת שעמורים מדויקים")
        assertEquals(DiagnosisSeverity.BLOCKER, item.severity)
        assertEquals(DiagnosisFix.EXACT_ALARM, item.fix)
    }

    /**
     * The strongest signal available: the app thinks an alarm is armed and the OS does
     * not know about it. That disagreement is what a user cannot possibly discover alone.
     */
    @Test
    fun `an armed alarm the system does not know about is a blocker`() {
        val item = itemTitled(
            healthy.copy(systemNextAlarmIsOurs = false, systemHasNextAlarm = false),
            "רישום במערכת",
        )
        assertEquals(DiagnosisSeverity.BLOCKER, item.severity)
        assertTrue(item.detail.contains("לא מדווחת על שעמור רשום"))
    }

    // ── Warnings, which must not be inflated into blockers ─────────────────

    @Test
    fun `another app owning the next alarm is a warning, not a blocker`() {
        val item = itemTitled(
            healthy.copy(systemNextAlarmIsOurs = false, systemHasNextAlarm = true),
            "רישום במערכת",
        )
        assertEquals(DiagnosisSeverity.WARNING, item.severity)
    }

    @Test
    fun `missing notifications degrades rather than blocks`() {
        // The alarm still rings; it is harder to stop. Calling that a blocker would cry
        // wolf and make the blockers worth less.
        assertEquals(
            DiagnosisSeverity.WARNING,
            itemTitled(healthy.copy(notificationsGranted = false), "הרשאת התראות").severity,
        )
    }

    @Test
    fun `missing full-screen intent degrades rather than blocks`() =
        assertEquals(
            DiagnosisSeverity.WARNING,
            itemTitled(healthy.copy(canUseFullScreenIntent = false), "מסך מלא בזמן צלצול").severity,
        )

    // ── The headline ───────────────────────────────────────────────────────

    @Test
    fun `one blocker reads in the singular`() =
        assertEquals(
            "נמצאה בעיה אחת שתמנע מהשעמור לצלצל.",
            diagnosisHeadline(diagnoseRinging(healthy.copy(ignoringBatteryOptimizations = false))),
        )

    @Test
    fun `several blockers are counted`() {
        val headline = diagnosisHeadline(
            diagnoseRinging(healthy.copy(ignoringBatteryOptimizations = false, alarmVolumeAudible = false)),
        )
        assertEquals("נמצאו 2 בעיות שימנעו מהשעמור לצלצל.", headline)
    }

    @Test
    fun `warnings alone say the alarm should still ring`() {
        val headline = diagnosisHeadline(diagnoseRinging(healthy.copy(notificationsGranted = false)))
        assertTrue(headline.contains("אמור לצלצל"))
    }

    /** "1 דברים" would read as a bug in the screen rather than a finding about the alarm. */
    @Test
    fun `one warning reads in the singular`() =
        assertEquals(
            "השעמור אמור לצלצל, אבל יש דבר אחד ששווה לתקן.",
            diagnosisHeadline(diagnoseRinging(healthy.copy(notificationsGranted = false))),
        )

    @Test
    fun `several warnings are counted`() =
        assertEquals(
            "השעמור אמור לצלצל, אבל יש 2 דברים ששווה לתקן.",
            diagnosisHeadline(diagnoseRinging(
                healthy.copy(notificationsGranted = false, canUseFullScreenIntent = false))),
        )

    /** A blocker outranks a warning: the headline must not soften a real failure. */
    @Test
    fun `a blocker wins over a warning in the headline`() {
        val headline = diagnosisHeadline(
            diagnoseRinging(healthy.copy(ignoringBatteryOptimizations = false, notificationsGranted = false)),
        )
        assertTrue(headline.contains("תמנע"))
    }
}
