package com.smartring.app.domain.model

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A named place to keep templates. One level: templates are in a folder or at the top. */
data class TemplateFolder(val id: String, val name: String, val createdAt: Long = 0L)

/**
 * A saved alarm configuration to start new alarms from.
 *
 * [alarm] holds only *settings* (see [asTemplateSettings]) — never an id, an enabled/frozen state, a
 * ring counter or a link to another device.
 */
data class AlarmTemplate(
    val id: String,
    val name: String,
    val folderId: String? = null,
    val alarm: Alarm,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

data class TemplateLibrary(
    val folders: List<TemplateFolder> = emptyList(),
    val templates: List<AlarmTemplate> = emptyList(),
) {
    fun template(id: String) = templates.firstOrNull { it.id == id }
    fun folder(id: String?) = id?.let { f -> folders.firstOrNull { it.id == f } }
    fun inFolder(folderId: String?) = templates.filter { it.folderId == folderId }
}

object TemplateLimits {
    const val MAX_TEMPLATES = 200
    const val MAX_FOLDERS = 30
    const val NAME_MAX_CHARS = 40
}

/**
 * The alarm's settings, stripped of everything that belongs to *that particular alarm*: its identity,
 * whether it is on or frozen, how many times it has rung, and any link to another device. A new alarm
 * made from the result is an ordinary local, enabled alarm.
 */
fun Alarm.asTemplateSettings(): Alarm = copy(
    id = 0L,
    isEnabled = true,
    isFrozen = false,
    occurrencesFired = 0,
    sync = AlarmSync.LOCAL,
    rings = rings.mapIndexed { i, r -> r.copy(id = 0L, alarmId = 0L, orderIndex = i) },
    specificDates = specificDates.map { it.copy(id = 0L, alarmId = 0L) },
)

/** A new alarm's starting point, and what the person must be told about it. */
data class TemplateInstance(val alarm: Alarm, val notices: List<String>)

/**
 * Turns a template into the starting point of a new alarm.
 *
 * Everything is copied as saved, except dates that have since passed. A date in the past can never
 * ring, and the editor refuses to save one — so keeping it would hand over a form that fails on the
 * first Save, while quietly dropping it would leave an alarm that behaves differently from the one
 * the person chose. So it is removed, and each removal is reported in [TemplateInstance.notices] to be
 * shown where the person will see it:
 *
 *  - a one-off date/time in the past is cleared (the time of day is kept);
 *  - past entries in the list of specific dates are removed;
 *  - a recurrence "until" date that has passed is cleared and the repeat becomes open-ended, which
 *    the notice says in so many words.
 *
 * [now] is a parameter so this is testable without depending on the day the test happens to run.
 */
fun instantiateTemplate(template: Alarm, now: Long = System.currentTimeMillis()): TemplateInstance {
    var alarm = template.asTemplateSettings()
    val notices = mutableListOf<String>()

    alarm.specificDateTime?.let { at ->
        if (at <= now) {
            val cal = Calendar.getInstance().apply { timeInMillis = at }
            alarm = alarm.copy(
                specificDateTime = null,
                hour = cal.get(Calendar.HOUR_OF_DAY),
                minute = cal.get(Calendar.MINUTE),
            )
            notices += "התאריך שנשמר בתבנית (${formatDay(at)}) כבר עבר, ולכן נמחק. בחרו תאריך חדש אם צריך."
        }
    }

    if (alarm.specificDates.isNotEmpty()) {
        val todayUtcMidnight = utcMidnightOfLocalDay(now)
        val (future, past) = alarm.specificDates.partition { it.date >= todayUtcMidnight }
        if (past.isNotEmpty()) {
            alarm = alarm.copy(specificDates = future)
            notices += if (past.size == 1) {
                "תאריך אחד מהתבנית (${formatUtcDay(past[0].date)}) כבר עבר, ולכן נמחק."
            } else {
                "${past.size} תאריכים מהתבנית כבר עברו, ולכן נמחקו."
            }
        }
    }

    val end = alarm.recurrenceEnd
    if (end.type == RecurrenceEndType.UNTIL && end.untilDate != null && end.untilDate <= now) {
        alarm = alarm.copy(recurrenceEnd = RecurrenceEnd(RecurrenceEndType.FOREVER, null, end.count))
        notices += "תאריך הסיום של החזרתיות בתבנית (${formatDay(end.untilDate)}) כבר עבר, ולכן נמחק — " +
            "השעמור יחזור בלי הגבלה עד שתבחרו תאריך סיום חדש."
    }
    return TemplateInstance(alarm, notices)
}

private fun formatDay(millis: Long): String = SimpleDateFormat("d.M.yyyy", Locale.US).format(Date(millis))

private fun formatUtcDay(millis: Long): String =
    SimpleDateFormat("d.M.yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

/** The UTC midnight that begins the *local* calendar day containing [now] — the convention [AlarmDate.date] uses. */
private fun utcMidnightOfLocalDay(now: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = now }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

// ── Library operations: pure, so every rule is a JVM test ─────────────────────

/** Why an operation changed nothing. */
enum class LibraryError { LIMIT_TEMPLATES, LIMIT_FOLDERS, NOT_FOUND, BLANK_NAME }

sealed interface LibraryResult {
    data class Ok(val library: TemplateLibrary, val id: String? = null) : LibraryResult
    data class Refused(val error: LibraryError) : LibraryResult
}

object TemplateOps {

    private fun cleanName(raw: String) = raw.trim().replace(Regex("\\s+"), " ").take(TemplateLimits.NAME_MAX_CHARS).trim()

    fun addTemplate(lib: TemplateLibrary, id: String, name: String, folderId: String?, alarm: Alarm, now: Long): LibraryResult {
        val n = cleanName(name)
        if (n.isEmpty()) return LibraryResult.Refused(LibraryError.BLANK_NAME)
        if (lib.templates.size >= TemplateLimits.MAX_TEMPLATES) return LibraryResult.Refused(LibraryError.LIMIT_TEMPLATES)
        // A folder that has gone away (deleted on another screen) means "top level", not an orphan.
        val folder = folderId?.takeIf { lib.folder(it) != null }
        val t = AlarmTemplate(id, n, folder, alarm.asTemplateSettings(), createdAt = now, updatedAt = now)
        return LibraryResult.Ok(lib.copy(templates = lib.templates + t), id)
    }

    /** Replaces a template's settings (and optionally its name) — "save the changes". */
    fun updateTemplate(lib: TemplateLibrary, id: String, name: String?, alarm: Alarm?, now: Long): LibraryResult {
        val t = lib.template(id) ?: return LibraryResult.Refused(LibraryError.NOT_FOUND)
        val n = name?.let { cleanName(it) }
        if (n != null && n.isEmpty()) return LibraryResult.Refused(LibraryError.BLANK_NAME)
        val updated = t.copy(name = n ?: t.name, alarm = alarm?.asTemplateSettings() ?: t.alarm, updatedAt = now)
        return LibraryResult.Ok(lib.copy(templates = lib.templates.map { if (it.id == id) updated else it }), id)
    }

    fun renameTemplate(lib: TemplateLibrary, id: String, name: String, now: Long) = updateTemplate(lib, id, name, null, now)

    fun moveTemplate(lib: TemplateLibrary, id: String, folderId: String?, now: Long): LibraryResult {
        val t = lib.template(id) ?: return LibraryResult.Refused(LibraryError.NOT_FOUND)
        if (folderId != null && lib.folder(folderId) == null) return LibraryResult.Refused(LibraryError.NOT_FOUND)
        return LibraryResult.Ok(lib.copy(templates = lib.templates.map { if (it.id == id) t.copy(folderId = folderId, updatedAt = now) else it }), id)
    }

    fun duplicateTemplate(lib: TemplateLibrary, id: String, newId: String, now: Long): LibraryResult {
        val t = lib.template(id) ?: return LibraryResult.Refused(LibraryError.NOT_FOUND)
        if (lib.templates.size >= TemplateLimits.MAX_TEMPLATES) return LibraryResult.Refused(LibraryError.LIMIT_TEMPLATES)
        val copy = t.copy(id = newId, name = cleanName("${t.name} (עותק)"), createdAt = now, updatedAt = now)
        return LibraryResult.Ok(lib.copy(templates = lib.templates + copy), newId)
    }

    fun deleteTemplate(lib: TemplateLibrary, id: String): LibraryResult {
        if (lib.template(id) == null) return LibraryResult.Refused(LibraryError.NOT_FOUND)
        return LibraryResult.Ok(lib.copy(templates = lib.templates.filterNot { it.id == id }))
    }

    fun addFolder(lib: TemplateLibrary, id: String, name: String, now: Long): LibraryResult {
        val n = cleanName(name)
        if (n.isEmpty()) return LibraryResult.Refused(LibraryError.BLANK_NAME)
        if (lib.folders.size >= TemplateLimits.MAX_FOLDERS) return LibraryResult.Refused(LibraryError.LIMIT_FOLDERS)
        return LibraryResult.Ok(lib.copy(folders = lib.folders + TemplateFolder(id, n, now)), id)
    }

    fun renameFolder(lib: TemplateLibrary, id: String, name: String): LibraryResult {
        if (lib.folder(id) == null) return LibraryResult.Refused(LibraryError.NOT_FOUND)
        val n = cleanName(name)
        if (n.isEmpty()) return LibraryResult.Refused(LibraryError.BLANK_NAME)
        return LibraryResult.Ok(lib.copy(folders = lib.folders.map { if (it.id == id) it.copy(name = n) else it }), id)
    }

    /**
     * Removes a folder. Its templates are **kept**, moved to the top level — deleting a folder to tidy up
     * must not be the one-tap way to lose a dozen templates. [deleteContents] is for the person who
     * means exactly that.
     */
    fun deleteFolder(lib: TemplateLibrary, id: String, deleteContents: Boolean = false): LibraryResult {
        if (lib.folder(id) == null) return LibraryResult.Refused(LibraryError.NOT_FOUND)
        val templates = if (deleteContents) lib.templates.filterNot { it.folderId == id }
                        else lib.templates.map { if (it.folderId == id) it.copy(folderId = null) else it }
        return LibraryResult.Ok(TemplateLibrary(lib.folders.filterNot { it.id == id }, templates))
    }
}
