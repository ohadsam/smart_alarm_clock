package com.smartring.app.sync

import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmDate
import com.smartring.app.domain.model.AlarmRing
import com.smartring.app.domain.model.AlarmSync
import com.smartring.app.domain.model.RecurrenceEnd
import com.smartring.app.domain.model.RecurrenceEndType
import com.smartring.app.domain.model.RepeatFrequency
import com.smartring.app.domain.model.VibrationMode
import com.smartring.app.sync.protocol.ProtocolException
import com.smartring.app.sync.protocol.SyncAlarm
import com.smartring.app.sync.protocol.SyncDate
import com.smartring.app.sync.protocol.SyncLimits
import com.smartring.app.sync.protocol.SyncRing
import com.smartring.app.sync.protocol.WireReader
import com.smartring.app.sync.protocol.WireWriter
import com.smartring.app.sync.protocol.alarm
import com.smartring.app.sync.protocol.cleanText
import com.smartring.app.sync.protocol.decodeSyncAlarm
import com.smartring.app.sync.protocol.encodeSyncAlarm
import com.smartring.app.sync.protocol.toAlarm
import com.smartring.app.sync.protocol.toSyncAlarm
import com.smartring.app.sync.protocol.validated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * An alarm arriving from another phone is untrusted input that will, if accepted, wake someone
 * at a time of its choosing. These pin what is accepted, what is repaired, what is refused, and
 * what is never carried across at all.
 */
class SyncAlarmTest {

    private val full = Alarm(
        name = "קום לעבודה", hour = 6, minute = 45,
        specificDateTime = 1_800_000_000_000L,
        isEnabled = true, isFrozen = true,
        repeatDaysBitmask = 0b0101010, repeatFrequency = RepeatFrequency.BIWEEKLY,
        recurrenceEnd = RecurrenceEnd(RecurrenceEndType.UNTIL, untilDate = 1_900_000_000_000L, count = 4),
        occurrencesFired = 7,
        specificDates = listOf(AlarmDate(date = 1_850_000_000_000L, label = "יום הולדת")),
        ringDurationSeconds = 90,
        rings = listOf(
            AlarmRing(orderIndex = 1, durationSeconds = 40, volumePercent = 70, ringtoneUri = "content://media/9", delayAfterSeconds = 5),
            AlarmRing(orderIndex = 0, durationSeconds = 30, volumePercent = 50, ringtoneUri = "default", delayAfterSeconds = 0),
        ),
        snoozeEnabled = true, snoozeMinutes = 9, snoozeMaxCount = 4,
        isShabbatMode = true, reminderText = "לקחת מפתחות",
        vibrationMode = VibrationMode.VIBRATION_THEN_SOUND, vibrationOnlySeconds = 17,
        crescendoEnabled = true, crescendoStartVolume = 25, crescendoStepSeconds = 20, crescendoStepPercent = 15,
    )

    // ── Codec ────────────────────────────────────────────────────────────────

    @Test
    fun `a fully populated alarm survives encoding`() {
        val dto = full.toSyncAlarm()
        assertEquals(dto, decodeSyncAlarm(encodeSyncAlarm(dto)))
    }

    @Test
    fun `an alarm with every optional field absent survives encoding`() {
        val dto = Alarm(name = "x").toSyncAlarm()
        assertEquals(dto, decodeSyncAlarm(encodeSyncAlarm(dto)))
        assertNull(dto.specificDateTime); assertNull(dto.reminderText); assertNull(dto.recurrenceUntilDate)
    }

    @Test
    fun `an encoded alarm stays far below the frame limit`() {
        val big = full.copy(
            name = "ש".repeat(SyncLimits.NAME_MAX_CHARS),
            reminderText = "ש".repeat(SyncLimits.REMINDER_MAX_CHARS),
            specificDates = List(SyncLimits.MAX_DATES) { AlarmDate(date = 1_850_000_000_000L + it, label = "ש".repeat(40)) },
            rings = List(SyncLimits.MAX_RINGS) { AlarmRing(orderIndex = it) },
        )
        assertTrue(encodeSyncAlarm(big.toSyncAlarm()).size < 16 * 1024)
    }

    @Test
    fun `decoding rejects trailing bytes`() {
        try { decodeSyncAlarm(encodeSyncAlarm(full.toSyncAlarm()) + byteArrayOf(1)); fail() }
        catch (expected: ProtocolException) { }
    }

    /**
     * A count on the wire is attacker-chosen. It has to be checked before anything is allocated or
     * looped over, or a few bytes become a denial of service.
     */
    @Test
    fun `an absurd date count is rejected without being honoured`() {
        val w = WireWriter()
        w.string("x", 100).u8(7).u8(0).optI64(null).bool(true).u8(0).u8(1).u8(0).optI64(null).i32(1)
        w.u16(65535)   // claims 65 535 dates and supplies none
        try { WireReader(w.toByteArray()).alarm(); fail() } catch (expected: ProtocolException) { }
    }

    @Test
    fun `an absurd round count is rejected without being honoured`() {
        val ok = full.copy(specificDates = emptyList()).toSyncAlarm()
        val bytes = encodeSyncAlarm(ok)
        // The round count is a single byte after the ring duration; find it by re-encoding with a
        // known count and patching the byte that follows the four-byte duration.
        val marker = ok.ringDurationSeconds
        var patched = false
        for (i in 0..bytes.size - 6) {
            val v = ((bytes[i].toInt() and 0xFF) shl 24) or ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (v == marker && bytes[i + 4].toInt() == ok.rings.size) { bytes[i + 4] = 250.toByte(); patched = true; break }
        }
        assertTrue("test setup could not find the round count", patched)
        try { decodeSyncAlarm(bytes); fail() } catch (expected: ProtocolException) { }
    }

    @Test
    fun `an unknown enum code is rejected, not defaulted`() {
        val w = WireWriter()
        w.string("x", 100).u8(7).u8(0).optI64(null).bool(true).u8(0)
        w.u8(77)   // repeat frequency 77 does not exist
        try { WireReader(w.toByteArray()).alarm(); fail() } catch (expected: ProtocolException) { }
    }

    // ── What never travels ───────────────────────────────────────────────────

    @Test
    fun `ringtones, frozen state and the occurrence counter are not carried`() {
        val received = full.toSyncAlarm().validated()!!.toAlarm()

        assertTrue("every round plays this phone's own default tone",
            received.rings.all { it.ringtoneUri == "default" })
        assertFalse("the user's own away switch is not the sender's to set", received.isFrozen)
        assertEquals("the count of rings that happened elsewhere means nothing here", 0, received.occurrencesFired)
    }

    @Test
    fun `rounds keep their order and their settings`() {
        val received = full.toSyncAlarm().validated()!!.toAlarm()
        assertEquals(listOf(30, 40), received.rings.map { it.durationSeconds })
        assertEquals(listOf(50, 70), received.rings.map { it.volumePercent })
        assertEquals(listOf(0, 1), received.rings.map { it.orderIndex })
    }

    @Test
    fun `an update keeps the local row id`() {
        val existing = Alarm(id = 17, name = "old", sync = AlarmSync(syncId = idOf('5')))
        assertEquals(17L, full.toSyncAlarm().validated()!!.toAlarm(base = existing).id)
        assertEquals(0L, full.toSyncAlarm().validated()!!.toAlarm(base = null).id)
    }

    // ── Validation: refuse the impossible ────────────────────────────────────

    private fun dto(block: (SyncAlarm) -> SyncAlarm) = block(full.toSyncAlarm())

    @Test
    fun `a valid alarm passes unchanged apart from cleaning`() {
        assertEquals(full.toSyncAlarm(), full.toSyncAlarm().validated())
    }

    @Test
    fun `an impossible time of day is refused`() {
        assertNull(dto { it.copy(hour = 24) }.validated())
        assertNull(dto { it.copy(hour = -1) }.validated())
        assertNull(dto { it.copy(minute = 60) }.validated())
    }

    @Test
    fun `a weekday mask beyond seven bits is refused`() {
        assertNull(dto { it.copy(repeatDaysBitmask = 128) }.validated())
        assertNull(dto { it.copy(repeatDaysBitmask = -1) }.validated())
    }

    @Test
    fun `an instant outside the plausible window is refused rather than clamped`() {
        assertNull(dto { it.copy(specificDateTime = 5L) }.validated())
        assertNull(dto { it.copy(specificDateTime = Long.MAX_VALUE) }.validated())
        assertNull(dto { it.copy(recurrenceUntilDate = 5L) }.validated())
        assertNull(dto { it.copy(specificDates = listOf(SyncDate(5L, null))) }.validated())
    }

    @Test
    fun `too many dates or rounds is refused`() {
        assertNull(dto { it.copy(specificDates = List(SyncLimits.MAX_DATES + 1) { SyncDate(1_850_000_000_000L, null) }) }.validated())
        assertNull(dto { it.copy(rings = List(SyncLimits.MAX_RINGS + 1) { SyncRing(30, 50, 0) }) }.validated())
    }

    // ── Validation: repair the tunables ──────────────────────────────────────

    @Test
    fun `durations and volumes are clamped into the editor's ranges`() {
        val v = dto {
            it.copy(
                ringDurationSeconds = 100_000, snoozeMinutes = 0, snoozeMaxCount = 99,
                vibrationOnlySeconds = 0, crescendoStartVolume = 100, crescendoStepSeconds = 0,
                crescendoStepPercent = 500, recurrenceCount = 0,
                rings = listOf(SyncRing(durationSeconds = 9999, volumePercent = 0, delayAfterSeconds = -5)),
            )
        }.validated()!!

        assertEquals(SyncLimits.RING_DURATION.last, v.ringDurationSeconds)
        assertEquals(SyncLimits.SNOOZE_MINUTES.first, v.snoozeMinutes)
        assertEquals(SyncLimits.SNOOZE_MAX_COUNT.last, v.snoozeMaxCount)
        assertEquals(SyncLimits.VIBRATION_ONLY.first, v.vibrationOnlySeconds)
        assertEquals(SyncLimits.CRESCENDO_START.last, v.crescendoStartVolume)
        assertEquals("a zero crescendo step would divide by zero in the ring plan",
            SyncLimits.CRESCENDO_STEP_SECONDS.first, v.crescendoStepSeconds)
        assertEquals(SyncLimits.CRESCENDO_STEP_PERCENT.last, v.crescendoStepPercent)
        assertEquals(SyncLimits.RECURRENCE_COUNT.first, v.recurrenceCount)
        assertEquals(SyncLimits.ROUND_DURATION.last, v.rings.single().durationSeconds)
        assertEquals(SyncLimits.ROUND_VOLUME.first, v.rings.single().volumePercent)
        assertEquals(SyncLimits.ROUND_DELAY.first, v.rings.single().delayAfterSeconds)
    }

    // ── Text: what a name is allowed to look like ────────────────────────────

    @Test
    fun `control characters are removed from text`() {
        assertEquals("ab", cleanText("a\u0000b\u001B", 10))
        assertEquals("a b", cleanText("a b", 10))
    }

    /**
     * "Mum" preceded by a right-to-left override displays as "muM". In an app whose entire premise
     * is knowing who set an alarm, a name that can display as something else is spoofing.
     */
    @Test
    fun `bidirectional override and isolate characters are removed`() {
        assertEquals("Mum", cleanText("‮Mum", 10))
        assertEquals("Mum", cleanText("M⁦u⁩m", 10))
        assertEquals("Mum", cleanText("‪Mum‬", 10))
    }

    @Test
    fun `text is cut at the limit by characters not bytes`() {
        assertEquals("שלוש", cleanText("שלושה", 4))
        assertEquals("😀😀", cleanText("😀😀😀", 2))
    }

    @Test
    fun `text is trimmed`() {
        assertEquals("x", cleanText("  x  ", 10))
        assertEquals("", cleanText("   ", 10))
    }

    @Test
    fun `an alarm name is cleaned as part of validation`() {
        val v = dto { it.copy(name = "‮" + "ש".repeat(500)) }.validated()!!
        assertEquals(SyncLimits.NAME_MAX_CHARS, v.name.length)
        assertFalse(v.name.contains('‮'))
    }

    @Test
    fun `an emptied reminder becomes absent`() {
        assertNull(dto { it.copy(reminderText = "\u0000\u0001") }.validated()!!.reminderText)
        assertNotNull(dto { it.copy(reminderText = "x") }.validated()!!.reminderText)
    }
}
