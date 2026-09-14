package com.smartring.app.data.repository

import com.smartring.app.data.db.AlarmDateEntity
import com.smartring.app.data.db.AlarmEntity
import com.smartring.app.data.db.AlarmLogEntity
import com.smartring.app.data.db.AlarmRingEntity
import com.smartring.app.data.db.AlarmWithDetails
import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmDate
import com.smartring.app.domain.model.AlarmRing
import com.smartring.app.domain.model.RecurrenceEnd
import com.smartring.app.domain.model.RecurrenceEndType
import com.smartring.app.domain.model.RepeatFrequency
import com.smartring.app.domain.model.VibrationMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The boundary every alarm crosses twice on its way to and from disk. It had no
 * direct coverage: AlarmDaoTest exercises it incidentally through Room, which means a
 * field quietly dropped from one of the two mapping functions still round-trips
 * correctly for every field that *is* mapped and looks entirely fine.
 *
 * A dropped field here is invisible until the user's setting doesn't take effect,
 * which for half of these is at 06:30 the next morning.
 */
class AlarmMapperTest {

    private val fullyPopulated = Alarm(
        id = 42,
        name = "בוקר",
        hour = 6, minute = 45,
        specificDateTime = 1_800_000_000_000L,
        isEnabled = false,
        isFrozen = true,
        repeatDaysBitmask = 0b0101010,
        repeatFrequency = RepeatFrequency.BIWEEKLY,
        recurrenceEnd = RecurrenceEnd(RecurrenceEndType.UNTIL, untilDate = 1_900_000_000_000L, count = 4),
        occurrencesFired = 7,
        ringDurationSeconds = 123,
        snoozeEnabled = true,
        snoozeMinutes = 9,
        snoozeMaxCount = 5,
        isShabbatMode = true,
        reminderText = "לקחת מפתחות",
        vibrationMode = VibrationMode.VIBRATION_THEN_SOUND,
        vibrationOnlySeconds = 17,
        crescendoEnabled = true,
        crescendoStartVolume = 25,
        crescendoStepSeconds = 20,
        crescendoStepPercent = 15,
    )

    /** Domain → entity → domain, the way saving and then reloading an alarm works. */
    private fun roundTrip(alarm: Alarm, rings: List<AlarmRing> = emptyList(), dates: List<AlarmDate> = emptyList()): Alarm =
        AlarmWithDetails(
            alarm = alarm.toEntity(),
            rings = rings.map { it.toEntity(alarm.id) },
            dates = dates.map { it.toEntity(alarm.id) },
        ).toDomain()

    @Test
    fun `every alarm field survives a save and reload`() {
        // Compared as whole objects on purpose: a field added to Alarm and forgotten in
        // either mapping direction fails here without anyone remembering to add a case.
        assertEquals(fullyPopulated, roundTrip(fullyPopulated))
    }

    @Test
    fun `an alarm with nothing set survives too`() {
        val bare = Alarm(id = 1, name = "x")
        assertEquals(bare, roundTrip(bare))
    }

    @Test
    fun `the nullable fields survive as null rather than becoming defaults`() {
        val noNulls = fullyPopulated.copy(specificDateTime = null, reminderText = null,
            recurrenceEnd = RecurrenceEnd(RecurrenceEndType.COUNT, untilDate = null, count = 3))
        assertEquals(noNulls, roundTrip(noNulls))
    }

    @Test
    fun `ring rounds come back in orderIndex order, not row order`() {
        // The playback sequence is defined by orderIndex; Room hands back whatever the
        // relation query returns, which is insertion order.
        val rings = listOf(
            AlarmRing(id = 3, alarmId = 42, orderIndex = 2, volumePercent = 30),
            AlarmRing(id = 1, alarmId = 42, orderIndex = 0, volumePercent = 10),
            AlarmRing(id = 2, alarmId = 42, orderIndex = 1, volumePercent = 20),
        )
        assertEquals(listOf(10, 20, 30), roundTrip(fullyPopulated, rings = rings).rings.map { it.volumePercent })
    }

    @Test
    fun `every ring field survives the round trip`() {
        val ring = AlarmRing(id = 5, alarmId = 42, orderIndex = 0, durationSeconds = 45,
            volumePercent = 65, ringtoneUri = "content://media/external/audio/media/9", delayAfterSeconds = 90)
        assertEquals(listOf(ring), roundTrip(fullyPopulated, rings = listOf(ring)).rings)
    }

    @Test
    fun `every specific-date field survives the round trip`() {
        val date = AlarmDate(id = 8, alarmId = 42, date = 1_850_000_000_000L, label = "יום הולדת")
        assertEquals(listOf(date), roundTrip(fullyPopulated, dates = listOf(date)).specificDates)
    }

    // ── Rows a newer build (or a hand-edited database) could leave behind ─────

    @Test
    fun `an unrecognised repeat frequency falls back instead of taking the list down`() {
        // Enum.valueOf throws, and it throws while mapping the *whole list*, so one bad
        // row used to mean an empty alarm screen rather than one odd-looking alarm.
        val entity = fullyPopulated.toEntity().copy(repeatFrequency = "FORTNIGHTLY_ISH")
        assertEquals(RepeatFrequency.WEEKLY, AlarmWithDetails(entity, emptyList(), emptyList()).toDomain().repeatFrequency)
    }

    @Test
    fun `an unrecognised vibration mode falls back`() {
        val entity = fullyPopulated.toEntity().copy(vibrationMode = "HAPTIC_SOMETHING")
        assertEquals(VibrationMode.SOUND_AND_VIBRATION,
            AlarmWithDetails(entity, emptyList(), emptyList()).toDomain().vibrationMode)
    }

    @Test
    fun `an unrecognised recurrence end type falls back to never ending`() {
        // FOREVER is the safe direction: the alternative is an alarm that reads as
        // already expired and silently stops ringing.
        val entity = fullyPopulated.toEntity().copy(recurrenceEndType = "UNTIL_FURTHER_NOTICE")
        assertEquals(RecurrenceEndType.FOREVER,
            AlarmWithDetails(entity, emptyList(), emptyList()).toDomain().recurrenceEnd.type)
    }

    @Test
    fun `an empty enum column falls back rather than throwing`() {
        val entity = AlarmEntity(id = 1, repeatFrequency = "", vibrationMode = "", recurrenceEndType = "")
        val alarm = AlarmWithDetails(entity, emptyList(), emptyList()).toDomain()
        assertEquals(RepeatFrequency.WEEKLY, alarm.repeatFrequency)
        assertEquals(VibrationMode.SOUND_AND_VIBRATION, alarm.vibrationMode)
        assertEquals(RecurrenceEndType.FOREVER, alarm.recurrenceEnd.type)
    }

    // ── History rows ─────────────────────────────────────────────────────────

    @Test
    fun `a history row whose alarm was deleted maps to id zero, not a crash`() {
        // alarm_logs' FK is ON DELETE SET NULL so history outlives the alarm it
        // belonged to; the domain model's id is non-null.
        val log = AlarmLogEntity(id = 2, alarmId = null, alarmName = "בוקר",
            firedAt = 1_700_000_000_000L, scheduledFor = 1_700_000_000_000L, action = "STOPPED").toDomain()
        assertEquals(0L, log.alarmId)
        assertEquals("בוקר", log.alarmName)
        assertEquals("STOPPED", log.action)
    }

    @Test
    fun `a history row for a live alarm keeps its id`() {
        val log = AlarmLogEntity(id = 2, alarmId = 42, alarmName = "בוקר", action = "FIRED").toDomain()
        assertEquals(42L, log.alarmId)
    }

    // ── Entity ids ───────────────────────────────────────────────────────────

    @Test
    fun `child rows are re-parented to the alarm they are saved under`() {
        // saveAlarmTransaction deletes and re-inserts the children, passing the real
        // (possibly just-generated) alarm id; a ring still carrying a stale alarmId
        // would be written against the wrong alarm or violate the foreign key.
        assertEquals(99L, AlarmRing(id = 1, alarmId = 7).toEntity(99L).alarmId)
        assertEquals(99L, AlarmDate(id = 1, alarmId = 7, date = 1L).toEntity(99L).alarmId)
    }

    @Test
    fun `a brand-new alarm maps to the id that tells Room to generate one`() {
        assertEquals(0L, Alarm(name = "x").toEntity().id)
        assertEquals(0L, AlarmRing().toEntity(5L).id)
    }
}
