package com.smartring.app.sync.protocol

import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmDate
import com.smartring.app.domain.model.AlarmRing
import com.smartring.app.domain.model.RecurrenceEnd
import com.smartring.app.domain.model.RecurrenceEndType
import com.smartring.app.domain.model.RepeatFrequency
import com.smartring.app.domain.model.VibrationMode

/**
 * An alarm as it travels between phones — the schedule and the ring settings, and nothing
 * that is local to one phone.
 *
 * What is deliberately **not** here:
 *  - **Ringtones.** `ringtoneUri` is a `content://` URI into *this* phone's media store; on
 *    another phone it points at nothing, or worse at something unrelated. Every received
 *    round plays the receiving phone's default tone.
 *  - **Frozen state and the occurrence counter.** Those describe what happened on one
 *    phone — the user's own "away" switch, how many times it has rung there.
 *  - **Local ids.** Row ids are per-device autoincrements; the sync identity travels
 *    separately, in the message.
 */
data class SyncAlarm(
    val name: String,
    val hour: Int,
    val minute: Int,
    val specificDateTime: Long?,
    val isEnabled: Boolean,
    val repeatDaysBitmask: Int,
    val repeatFrequency: RepeatFrequency,
    val recurrenceEndType: RecurrenceEndType,
    val recurrenceUntilDate: Long?,
    val recurrenceCount: Int,
    val specificDates: List<SyncDate>,
    val ringDurationSeconds: Int,
    val rings: List<SyncRing>,
    val snoozeEnabled: Boolean,
    val snoozeMinutes: Int,
    val snoozeMaxCount: Int,
    val isShabbatMode: Boolean,
    val reminderText: String?,
    val vibrationMode: VibrationMode,
    val vibrationOnlySeconds: Int,
    val crescendoEnabled: Boolean,
    val crescendoStartVolume: Int,
    val crescendoStepSeconds: Int,
    val crescendoStepPercent: Int,
)

data class SyncDate(val date: Long, val label: String?)

data class SyncRing(val durationSeconds: Int, val volumePercent: Int, val delayAfterSeconds: Int)

/**
 * The limits a received alarm is held to.
 *
 * The numeric ranges are the editor's own sliders — a paired parent should not be able to
 * set something the app's UI could never produce, because the rest of the app was never
 * written to cope with it (a 40-hour ring, a 0-second crescendo step that divides by zero).
 */
object SyncLimits {
    const val NAME_MAX_CHARS = 60
    const val REMINDER_MAX_CHARS = 500
    const val DATE_LABEL_MAX_CHARS = 40
    const val MAX_RINGS = 10
    const val MAX_DATES = 100

    // Byte caps for the wire: a UTF-8 character is at most 4 bytes.
    const val NAME_MAX_BYTES = NAME_MAX_CHARS * 4
    const val REMINDER_MAX_BYTES = REMINDER_MAX_CHARS * 4
    const val DATE_LABEL_MAX_BYTES = DATE_LABEL_MAX_CHARS * 4

    val RING_DURATION = 5..600
    val VIBRATION_ONLY = 3..120
    val CRESCENDO_START = 5..80
    val CRESCENDO_STEP_SECONDS = 5..60
    val CRESCENDO_STEP_PERCENT = 5..30
    val SNOOZE_MINUTES = 1..60
    val SNOOZE_MAX_COUNT = 1..10
    val RECURRENCE_COUNT = 1..100
    val ROUND_DURATION = 5..300
    val ROUND_VOLUME = 10..100
    val ROUND_DELAY = 0..600

    /**
     * Instants outside roughly 2001–2103 are not alarms, they are corruption. Rejecting rather
     * than clamping: a "fire at" time silently moved to the nearest bound would ring at a moment
     * nobody chose.
     */
    const val EARLIEST_INSTANT = 1_000_000_000_000L
    const val LATEST_INSTANT = 4_200_000_000_000L
}

/**
 * Cleans text that came from another device before it is shown to this user.
 *
 * Control characters are dropped — they can corrupt a notification or a log line — and so
 * are the bidirectional override and isolate characters, which let a name *display* as
 * something other than what it is. ("Mum" with a right-to-left override in front reads as
 * "muM"; in an app whose whole point is trusting who set an alarm, that is spoofing.)
 */
internal fun cleanText(input: String, maxChars: Int): String {
    val sb = StringBuilder()
    var count = 0
    var i = 0
    while (i < input.length && count < maxChars) {
        val cp = input.codePointAt(i)
        i += Character.charCount(cp)
        val bidi = cp in 0x202A..0x202E || cp in 0x2066..0x2069
        if (Character.isISOControl(cp) || bidi) continue
        sb.appendCodePoint(cp)
        count++
    }
    return sb.toString().trim()
}

/**
 * Validates a received alarm and returns the version this phone will keep, or null if it
 * is not an alarm at all.
 *
 * Two different treatments, on purpose. **Structurally impossible** values — an hour of 99, a
 * date in the year 3000, more rounds than the editor allows — mean the message is corrupt or
 * hostile, and the whole alarm is refused: guessing what was meant would ring at a time
 * nobody chose. **Tunables** — durations, volumes, the snooze length — are clamped into the
 * range the editor allows, because the only harm in a slightly-wrong volume is a slightly-wrong
 * volume.
 */
fun SyncAlarm.validated(): SyncAlarm? {
    if (hour !in 0..23 || minute !in 0..59) return null
    if (repeatDaysBitmask !in 0..0b1111111) return null
    if (specificDateTime != null &&
        specificDateTime !in SyncLimits.EARLIEST_INSTANT..SyncLimits.LATEST_INSTANT) return null
    if (recurrenceUntilDate != null &&
        recurrenceUntilDate !in SyncLimits.EARLIEST_INSTANT..SyncLimits.LATEST_INSTANT) return null
    if (specificDates.size > SyncLimits.MAX_DATES) return null
    if (specificDates.any { it.date !in SyncLimits.EARLIEST_INSTANT..SyncLimits.LATEST_INSTANT }) return null
    if (rings.size > SyncLimits.MAX_RINGS) return null

    return copy(
        name = cleanText(name, SyncLimits.NAME_MAX_CHARS),
        reminderText = reminderText?.let { cleanText(it, SyncLimits.REMINDER_MAX_CHARS) }?.takeIf { it.isNotEmpty() },
        recurrenceCount = recurrenceCount.coerceIn(SyncLimits.RECURRENCE_COUNT),
        specificDates = specificDates.map {
            it.copy(label = it.label?.let { l -> cleanText(l, SyncLimits.DATE_LABEL_MAX_CHARS) }?.takeIf { l -> l.isNotEmpty() })
        },
        ringDurationSeconds = ringDurationSeconds.coerceIn(SyncLimits.RING_DURATION),
        rings = rings.map {
            SyncRing(
                durationSeconds = it.durationSeconds.coerceIn(SyncLimits.ROUND_DURATION),
                volumePercent = it.volumePercent.coerceIn(SyncLimits.ROUND_VOLUME),
                delayAfterSeconds = it.delayAfterSeconds.coerceIn(SyncLimits.ROUND_DELAY),
            )
        },
        snoozeMinutes = snoozeMinutes.coerceIn(SyncLimits.SNOOZE_MINUTES),
        snoozeMaxCount = snoozeMaxCount.coerceIn(SyncLimits.SNOOZE_MAX_COUNT),
        vibrationOnlySeconds = vibrationOnlySeconds.coerceIn(SyncLimits.VIBRATION_ONLY),
        crescendoStartVolume = crescendoStartVolume.coerceIn(SyncLimits.CRESCENDO_START),
        crescendoStepSeconds = crescendoStepSeconds.coerceIn(SyncLimits.CRESCENDO_STEP_SECONDS),
        crescendoStepPercent = crescendoStepPercent.coerceIn(SyncLimits.CRESCENDO_STEP_PERCENT),
    )
}

// ── Domain mapping ──────────────────────────────────────────────────────────

/** The part of this alarm another phone needs. Never carries ringtones, frozen state or counters. */
fun Alarm.toSyncAlarm() = SyncAlarm(
    name = name,
    hour = hour,
    minute = minute,
    specificDateTime = specificDateTime,
    isEnabled = isEnabled,
    repeatDaysBitmask = repeatDaysBitmask,
    repeatFrequency = repeatFrequency,
    recurrenceEndType = recurrenceEnd.type,
    recurrenceUntilDate = recurrenceEnd.untilDate,
    recurrenceCount = recurrenceEnd.count,
    specificDates = specificDates.map { SyncDate(it.date, it.label) },
    ringDurationSeconds = ringDurationSeconds,
    rings = rings.sortedBy { it.orderIndex }
        .map { SyncRing(it.durationSeconds, it.volumePercent, it.delayAfterSeconds) },
    snoozeEnabled = snoozeEnabled,
    snoozeMinutes = snoozeMinutes,
    snoozeMaxCount = snoozeMaxCount,
    isShabbatMode = isShabbatMode,
    reminderText = reminderText,
    vibrationMode = vibrationMode,
    vibrationOnlySeconds = vibrationOnlySeconds,
    crescendoEnabled = crescendoEnabled,
    crescendoStartVolume = crescendoStartVolume,
    crescendoStepSeconds = crescendoStepSeconds,
    crescendoStepPercent = crescendoStepPercent,
)

/**
 * Builds the alarm this phone will store from a (validated) received one.
 *
 * Starts from [base] — the copy this phone already has, if any — so that what is local to this
 * phone survives an update: the row id, which the scheduler keys its PendingIntents off. What
 * is *not* kept is the occurrence counter and the frozen flag: an update from the owner is a
 * fresh instruction, and a one-time alarm that already rang here must be able to ring again
 * once its owner re-sets it.
 */
fun SyncAlarm.toAlarm(base: Alarm? = null) = Alarm(
    id = base?.id ?: 0L,
    name = name,
    hour = hour,
    minute = minute,
    specificDateTime = specificDateTime,
    isEnabled = isEnabled,
    isFrozen = false,
    repeatDaysBitmask = repeatDaysBitmask,
    repeatFrequency = repeatFrequency,
    recurrenceEnd = RecurrenceEnd(recurrenceEndType, recurrenceUntilDate, recurrenceCount),
    occurrencesFired = 0,
    specificDates = specificDates.map { AlarmDate(date = it.date, label = it.label) },
    ringDurationSeconds = ringDurationSeconds,
    rings = rings.mapIndexed { i, r ->
        // "default", always: see the class comment on ringtones.
        AlarmRing(orderIndex = i, durationSeconds = r.durationSeconds, volumePercent = r.volumePercent,
            ringtoneUri = "default", delayAfterSeconds = r.delayAfterSeconds)
    },
    snoozeEnabled = snoozeEnabled,
    snoozeMinutes = snoozeMinutes,
    snoozeMaxCount = snoozeMaxCount,
    isShabbatMode = isShabbatMode,
    reminderText = reminderText,
    vibrationMode = vibrationMode,
    vibrationOnlySeconds = vibrationOnlySeconds,
    crescendoEnabled = crescendoEnabled,
    crescendoStartVolume = crescendoStartVolume,
    crescendoStepSeconds = crescendoStepSeconds,
    crescendoStepPercent = crescendoStepPercent,
)

// ── Wire codec ──────────────────────────────────────────────────────────────

private val FREQUENCY_CODES = mapOf(
    RepeatFrequency.NONE to 0, RepeatFrequency.WEEKLY to 1,
    RepeatFrequency.BIWEEKLY to 2, RepeatFrequency.MONTHLY to 3,
)
private val END_TYPE_CODES = mapOf(
    RecurrenceEndType.FOREVER to 0, RecurrenceEndType.UNTIL to 1, RecurrenceEndType.COUNT to 2,
)
private val VIBRATION_CODES = mapOf(
    VibrationMode.SOUND_ONLY to 0, VibrationMode.VIBRATION_ONLY to 1,
    VibrationMode.SOUND_AND_VIBRATION to 2, VibrationMode.VIBRATION_THEN_SOUND to 3,
)

// Explicit codes rather than `ordinal`: an enum reordered in some later release must not
// silently change what an old phone's message means.
private fun <T> Map<T, Int>.decode(code: Int, what: String): T =
    entries.firstOrNull { it.value == code }?.key ?: throw ProtocolException("unknown $what code $code")

fun WireWriter.alarm(a: SyncAlarm) = apply {
    string(a.name, SyncLimits.NAME_MAX_BYTES)
    u8(a.hour); u8(a.minute)
    optI64(a.specificDateTime)
    bool(a.isEnabled)
    u8(a.repeatDaysBitmask)
    u8(FREQUENCY_CODES.getValue(a.repeatFrequency))
    u8(END_TYPE_CODES.getValue(a.recurrenceEndType))
    optI64(a.recurrenceUntilDate)
    i32(a.recurrenceCount)
    require(a.specificDates.size <= SyncLimits.MAX_DATES) { "too many dates" }
    u16(a.specificDates.size)
    for (d in a.specificDates) { i64(d.date); optString(d.label, SyncLimits.DATE_LABEL_MAX_BYTES) }
    i32(a.ringDurationSeconds)
    require(a.rings.size <= SyncLimits.MAX_RINGS) { "too many rounds" }
    u8(a.rings.size)
    for (r in a.rings) { i32(r.durationSeconds); i32(r.volumePercent); i32(r.delayAfterSeconds) }
    bool(a.snoozeEnabled); i32(a.snoozeMinutes); i32(a.snoozeMaxCount)
    bool(a.isShabbatMode)
    optString(a.reminderText, SyncLimits.REMINDER_MAX_BYTES)
    u8(VIBRATION_CODES.getValue(a.vibrationMode)); i32(a.vibrationOnlySeconds)
    bool(a.crescendoEnabled); i32(a.crescendoStartVolume); i32(a.crescendoStepSeconds); i32(a.crescendoStepPercent)
}

fun WireReader.alarm(): SyncAlarm {
    val name = string(SyncLimits.NAME_MAX_BYTES)
    val hour = u8(); val minute = u8()
    val specificDateTime = optI64()
    val isEnabled = bool()
    val days = u8()
    val frequency = FREQUENCY_CODES.decode(u8(), "repeat frequency")
    val endType = END_TYPE_CODES.decode(u8(), "recurrence end")
    val until = optI64()
    val count = i32()
    val dateCount = u16()
    // Checked BEFORE the loop: the count comes off the wire, and allocating or looping on an
    // attacker-chosen number is how a 4-byte message becomes a denial of service.
    if (dateCount > SyncLimits.MAX_DATES) throw ProtocolException("too many dates")
    val dates = List(dateCount) { SyncDate(i64(), optString(SyncLimits.DATE_LABEL_MAX_BYTES)) }
    val ringDuration = i32()
    val ringCount = u8()
    if (ringCount > SyncLimits.MAX_RINGS) throw ProtocolException("too many rounds")
    val rings = List(ringCount) { SyncRing(i32(), i32(), i32()) }
    val snoozeEnabled = bool(); val snoozeMinutes = i32(); val snoozeMax = i32()
    val shabbat = bool()
    val reminder = optString(SyncLimits.REMINDER_MAX_BYTES)
    val vibration = VIBRATION_CODES.decode(u8(), "vibration mode"); val vibrationOnly = i32()
    val crescendo = bool(); val cStart = i32(); val cSeconds = i32(); val cPercent = i32()
    return SyncAlarm(
        name, hour, minute, specificDateTime, isEnabled, days, frequency, endType, until, count,
        dates, ringDuration, rings, snoozeEnabled, snoozeMinutes, snoozeMax, shabbat, reminder,
        vibration, vibrationOnly, crescendo, cStart, cSeconds, cPercent,
    )
}

fun encodeSyncAlarm(a: SyncAlarm): ByteArray = WireWriter().alarm(a).toByteArray()

/** Decodes a stored/forwarded payload; throws [ProtocolException] on anything but exactly one alarm. */
fun decodeSyncAlarm(bytes: ByteArray): SyncAlarm {
    val r = WireReader(bytes)
    return r.alarm().also { r.requireEnd() }
}
