package com.smartring.app.util

import java.util.Calendar

/**
 * The one-tap alarm shortcuts, as the user configures them.
 *
 * v1.8.1 shipped three hard-coded chips — "עוד שעה", "עוד 8 שעות", "מחר at the default
 * hour". Hard-coding was the wrong call: the whole point of a shortcut is that it matches
 * what *this* person keeps doing, and the set that suits someone napping ("עוד 20 דק׳")
 * has nothing in common with the set that suits someone going to bed. The shortcuts are
 * now a list the user owns.
 *
 * Two [kind]s, because the two questions people actually ask are different:
 * - [QuickPresetKind.RELATIVE] — "wake me in N minutes", counted from the moment of the
 *   tap. A nap, a timer, a reminder to move the laundry.
 * - [QuickPresetKind.TIME_OF_DAY] — "wake me at 07:00", meaning the next 07:00 there is.
 *
 * Everything here is pure and takes `now` as a parameter: the interesting cases are all
 * boundaries (a preset for 07:00 tapped at 06:59 versus 07:01, a relative preset that
 * crosses midnight), and boundaries tested against the real clock are boundaries tested
 * on whatever minute CI happens to run.
 */

enum class QuickPresetKind { RELATIVE, TIME_OF_DAY }

data class QuickPreset(
    /** Stable across edits and reorders, so a row can be edited or removed by identity. */
    val id: Long,
    val kind: QuickPresetKind,
    /** [QuickPresetKind.RELATIVE] only: minutes from the moment of the tap. */
    val minutes: Int = 30,
    /** [QuickPresetKind.TIME_OF_DAY] only. */
    val hour: Int = 7,
    val minute: Int = 0,
    /** Shown among the chips on the main screen. */
    val showInApp: Boolean = true,
    /** Shown in the widget's quick-actions panel. */
    val showInWidget: Boolean = true,
) {
    /** Clamped to values that can actually be acted on, so a corrupt store cannot
     *  produce a chip that schedules something impossible. */
    fun sanitized(): QuickPreset = copy(
        minutes = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES),
        hour = hour.coerceIn(0, 23),
        minute = minute.coerceIn(0, 59),
    )

    companion object {
        const val MIN_MINUTES = 1

        /** A week. Past this a relative shortcut is not a shortcut, it is a date. */
        const val MAX_MINUTES = 7 * 24 * 60
    }
}

/**
 * When this preset would ring, tapped at [nowMillis].
 *
 * A TIME_OF_DAY preset means the **next** occurrence of that time: today when it is still
 * ahead, tomorrow once it has passed. That differs from v1.8.1's "מחר" chip, which was
 * always tomorrow — deliberately, because it said "מחר" and a control must do what it
 * says. The same rule applies here and points the other way: this chip is labelled with
 * the day it will actually land on ([presetLabel]), so "next occurrence" is what it says
 * and what it does.
 *
 * Seconds are zeroed either way, so a quick alarm rings on a whole minute like every other
 * alarm in the app.
 */
fun quickPresetFireAt(preset: QuickPreset, nowMillis: Long = System.currentTimeMillis()): Long {
    val p = preset.sanitized()
    return when (p.kind) {
        QuickPresetKind.RELATIVE -> Calendar.getInstance().apply {
            timeInMillis = nowMillis
            add(Calendar.MINUTE, p.minutes)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        QuickPresetKind.TIME_OF_DAY -> {
            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, p.hour)
                set(Calendar.MINUTE, p.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // Strictly greater: a preset for 07:00 tapped exactly at 07:00:00 means the
            // next one, not one that is already due. `schedule()` would cancel a trigger
            // in the past and the tap would do nothing visible.
            if (cal.timeInMillis > nowMillis) cal.timeInMillis
            else cal.apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        }
    }
}

/**
 * What the chip says.
 *
 * A relative preset reads as the interval ("עוד 20 דק׳"); a time-of-day preset reads as
 * the day it will land on plus the time ("מחר 07:00"), because that is the part a person
 * needs to be sure of before tapping — the same reason the widget rows gained a day label.
 */
fun presetLabel(preset: QuickPreset, nowMillis: Long = System.currentTimeMillis()): String {
    val p = preset.sanitized()
    return when (p.kind) {
        QuickPresetKind.RELATIVE -> relativeLabel(p.minutes)
        QuickPresetKind.TIME_OF_DAY -> {
            val at = quickPresetFireAt(p, nowMillis)
            "${widgetDayLabel(at, nowMillis)} %02d:%02d".format(p.hour, p.minute)
        }
    }
}

/** "עוד 20 דק׳" / "עוד שעה" / "עוד 8 שעות" / "עוד 1 שע׳ 30 דק׳". */
private fun relativeLabel(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "עוד $m דק׳"
        m == 0 && h == 1 -> "עוד שעה"
        m == 0 && h == 2 -> "עוד שעתיים"
        m == 0 -> "עוד $h שעות"
        else -> "עוד $h שע׳ $m דק׳"
    }
}

/**
 * The shortcuts a fresh install starts with.
 *
 * Chosen to span the range rather than to cluster: a short nap, a long nap, an evening's
 * sleep, and a morning time. Someone who wants something else edits the list — that is
 * the feature — but the starting set should already be useful without being edited.
 */
val BUILT_IN_QUICK_PRESETS: List<QuickPreset> = listOf(
    QuickPreset(id = 1, kind = QuickPresetKind.RELATIVE, minutes = 10),
    QuickPreset(id = 2, kind = QuickPresetKind.RELATIVE, minutes = 30),
    QuickPreset(id = 3, kind = QuickPresetKind.RELATIVE, minutes = 60),
    QuickPreset(id = 4, kind = QuickPresetKind.RELATIVE, minutes = 8 * 60),
    QuickPreset(id = 5, kind = QuickPresetKind.TIME_OF_DAY, hour = 7, minute = 0),
)

/**
 * How many chips each surface shows, and the ceilings on those numbers.
 *
 * Two separate counts because the two surfaces have nothing in common dimensionally: the
 * app's row scrolls horizontally and can afford several, while a widget panel is a few
 * cells of someone's home screen and a sixth row there means the first is off-screen.
 */
data class QuickPresetLimits(
    val maxInApp: Int = 4,
    val maxInWidget: Int = 3,
) {
    fun sanitized() = QuickPresetLimits(
        maxInApp = maxInApp.coerceIn(0, MAX_IN_APP_CEILING),
        maxInWidget = maxInWidget.coerceIn(0, MAX_IN_WIDGET_CEILING),
    )

    companion object {
        const val MAX_IN_APP_CEILING = 8
        const val MAX_IN_WIDGET_CEILING = 5
        val BUILT_IN = QuickPresetLimits()
    }
}

/** The chips the main screen shows: those marked for it, capped by the app's limit. */
fun presetsForApp(presets: List<QuickPreset>, limits: QuickPresetLimits): List<QuickPreset> =
    presets.filter { it.showInApp }.take(limits.sanitized().maxInApp)

/** The chips the widget panel shows: those marked for it, capped by the widget's limit. */
fun presetsForWidget(presets: List<QuickPreset>, limits: QuickPresetLimits): List<QuickPreset> =
    presets.filter { it.showInWidget }.take(limits.sanitized().maxInWidget)

// ── Storage ──────────────────────────────────────────────────────────────────
//
// Encoded by hand rather than with kotlinx.serialization, which this project does not
// depend on: adding a compiler plugin and a runtime to store five rows of seven numbers
// is more moving parts than the problem has. The format is deliberately boring —
// positional fields, no nesting — and it is decoded defensively, because a preferences
// file that has been corrupted or written by a future version must degrade to the
// built-in presets rather than take the settings screen down.

private const val FIELD_SEP = ","
private const val RECORD_SEP = "|"

fun encodeQuickPresets(presets: List<QuickPreset>): String =
    presets.joinToString(RECORD_SEP) { p ->
        listOf(
            p.id,
            p.kind.name,
            p.minutes,
            p.hour,
            p.minute,
            if (p.showInApp) 1 else 0,
            if (p.showInWidget) 1 else 0,
        ).joinToString(FIELD_SEP)
    }

/**
 * Decodes what [encodeQuickPresets] wrote.
 *
 * Any record that cannot be read is dropped rather than failing the whole list, and a
 * string that yields nothing usable falls back to [BUILT_IN_QUICK_PRESETS] — an empty
 * shortcut row is indistinguishable from the feature being broken, whereas the built-ins
 * are always safe to show.
 */
fun decodeQuickPresets(raw: String?): List<QuickPreset> {
    if (raw.isNullOrBlank()) return BUILT_IN_QUICK_PRESETS
    val parsed = raw.split(RECORD_SEP).mapNotNull { record ->
        val f = record.split(FIELD_SEP)
        if (f.size < 7) return@mapNotNull null
        val id = f[0].toLongOrNull() ?: return@mapNotNull null
        val kind = runCatching { QuickPresetKind.valueOf(f[1]) }.getOrNull() ?: return@mapNotNull null
        QuickPreset(
            id = id,
            kind = kind,
            minutes = f[2].toIntOrNull() ?: return@mapNotNull null,
            hour = f[3].toIntOrNull() ?: return@mapNotNull null,
            minute = f[4].toIntOrNull() ?: return@mapNotNull null,
            showInApp = f[5] == "1",
            showInWidget = f[6] == "1",
        ).sanitized()
    }
    return parsed.ifEmpty { BUILT_IN_QUICK_PRESETS }
}

/** The next free id, so a new preset never collides with one already stored. */
fun nextPresetId(presets: List<QuickPreset>): Long = (presets.maxOfOrNull { it.id } ?: 0L) + 1L
