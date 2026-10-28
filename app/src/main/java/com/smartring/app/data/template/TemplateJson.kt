package com.smartring.app.data.template

import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmDate
import com.smartring.app.domain.model.AlarmRing
import com.smartring.app.domain.model.AlarmTemplate
import com.smartring.app.domain.model.RecurrenceEnd
import com.smartring.app.domain.model.RecurrenceEndType
import com.smartring.app.domain.model.RepeatFrequency
import com.smartring.app.domain.model.TemplateFolder
import com.smartring.app.domain.model.TemplateLibrary
import com.smartring.app.domain.model.TemplateLimits
import com.smartring.app.domain.model.VibrationMode
import com.smartring.app.domain.model.asTemplateSettings
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The template library as text, for the DataStore it lives in.
 *
 * JSON rather than a bespoke format because it has to survive the app changing: a field added later
 * is simply absent from older documents and takes its default, an unknown enum name takes the default
 * instead of failing, and an entry that cannot be read is dropped without taking the rest with it.
 * Losing one template to a bad record is a nuisance; losing all of them to one is the failure this is
 * written to avoid.
 */
object TemplateJson {
    private const val VERSION = 1

    fun encode(lib: TemplateLibrary): String = JSONObject().apply {
        put("v", VERSION)
        put("folders", JSONArray().apply {
            lib.folders.forEach { f -> put(JSONObject().put("id", f.id).put("name", f.name).put("createdAt", f.createdAt)) }
        })
        put("templates", JSONArray().apply { lib.templates.forEach { put(encodeTemplate(it)) } })
    }.toString()

    /** An empty library for null, blank or unreadable input. */
    fun decode(raw: String?): TemplateLibrary {
        if (raw.isNullOrBlank()) return TemplateLibrary()
        val root = try { JSONObject(raw) } catch (e: JSONException) { return TemplateLibrary() }
        val folders = root.optJSONArray("folders").objects().mapNotNull { o ->
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TemplateFolder(id, name.take(TemplateLimits.NAME_MAX_CHARS), o.optLong("createdAt"))
        }.distinctBy { it.id }.take(TemplateLimits.MAX_FOLDERS)
        val folderIds = folders.map { it.id }.toSet()
        val templates = root.optJSONArray("templates").objects().mapNotNull { o ->
            try { decodeTemplate(o, folderIds) } catch (e: JSONException) { null }
        }.distinctBy { it.id }.take(TemplateLimits.MAX_TEMPLATES)
        return TemplateLibrary(folders, templates)
    }

    private fun encodeTemplate(t: AlarmTemplate) = JSONObject().apply {
        put("id", t.id); put("name", t.name)
        t.folderId?.let { put("folderId", it) }
        put("createdAt", t.createdAt); put("updatedAt", t.updatedAt)
        put("alarm", encodeAlarm(t.alarm))
    }

    private fun decodeTemplate(o: JSONObject, folderIds: Set<String>): AlarmTemplate? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val name = o.optString("name").takeIf { it.isNotBlank() } ?: return null
        val alarmJson = o.optJSONObject("alarm") ?: return null
        return AlarmTemplate(
            id = id,
            name = name.take(TemplateLimits.NAME_MAX_CHARS),
            // A folder that no longer exists means the top level.
            folderId = o.optString("folderId").takeIf { it in folderIds },
            alarm = decodeAlarm(alarmJson).asTemplateSettings(),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
        )
    }

    private fun encodeAlarm(a: Alarm) = JSONObject().apply {
        put("name", a.name)
        put("hour", a.hour); put("minute", a.minute)
        a.specificDateTime?.let { put("specificDateTime", it) }
        put("repeatDaysBitmask", a.repeatDaysBitmask)
        put("repeatFrequency", a.repeatFrequency.name)
        put("recurrenceEnd", JSONObject().apply {
            put("type", a.recurrenceEnd.type.name)
            a.recurrenceEnd.untilDate?.let { put("untilDate", it) }
            put("count", a.recurrenceEnd.count)
        })
        put("specificDates", JSONArray().apply {
            a.specificDates.forEach { d -> put(JSONObject().put("date", d.date).apply { d.label?.let { put("label", it) } }) }
        })
        put("ringDurationSeconds", a.ringDurationSeconds)
        put("rings", JSONArray().apply {
            a.rings.forEach { r ->
                put(JSONObject().put("durationSeconds", r.durationSeconds).put("volumePercent", r.volumePercent)
                    .put("ringtoneUri", r.ringtoneUri).put("delayAfterSeconds", r.delayAfterSeconds))
            }
        })
        put("snoozeEnabled", a.snoozeEnabled); put("snoozeMinutes", a.snoozeMinutes); put("snoozeMaxCount", a.snoozeMaxCount)
        put("isShabbatMode", a.isShabbatMode)
        a.reminderText?.let { put("reminderText", it) }
        put("vibrationMode", a.vibrationMode.name); put("vibrationOnlySeconds", a.vibrationOnlySeconds)
        put("crescendoEnabled", a.crescendoEnabled); put("crescendoStartVolume", a.crescendoStartVolume)
        put("crescendoStepSeconds", a.crescendoStepSeconds); put("crescendoStepPercent", a.crescendoStepPercent)
    }

    private fun decodeAlarm(o: JSONObject): Alarm {
        val d = Alarm()
        val end = o.optJSONObject("recurrenceEnd")
        return Alarm(
            name = o.optString("name", d.name),
            hour = o.optInt("hour", d.hour).coerceIn(0, 23),
            minute = o.optInt("minute", d.minute).coerceIn(0, 59),
            specificDateTime = if (o.has("specificDateTime")) o.optLong("specificDateTime") else null,
            repeatDaysBitmask = o.optInt("repeatDaysBitmask", d.repeatDaysBitmask) and 0x7F,
            repeatFrequency = enumOr(o.optString("repeatFrequency"), d.repeatFrequency),
            recurrenceEnd = RecurrenceEnd(
                type = enumOr(end?.optString("type"), RecurrenceEndType.FOREVER),
                untilDate = if (end?.has("untilDate") == true) end.optLong("untilDate") else null,
                count = (end?.optInt("count", 10) ?: 10).coerceIn(1, 1000),
            ),
            specificDates = o.optJSONArray("specificDates").objects().map {
                AlarmDate(date = it.optLong("date"), label = if (it.has("label")) it.optString("label") else null)
            },
            ringDurationSeconds = o.optInt("ringDurationSeconds", d.ringDurationSeconds).coerceIn(1, 3600),
            rings = o.optJSONArray("rings").objects().take(10).mapIndexed { i, r ->
                AlarmRing(
                    orderIndex = i,
                    durationSeconds = r.optInt("durationSeconds", 60).coerceIn(1, 3600),
                    volumePercent = r.optInt("volumePercent", 100).coerceIn(0, 100),
                    ringtoneUri = r.optString("ringtoneUri", "default").ifEmpty { "default" },
                    delayAfterSeconds = r.optInt("delayAfterSeconds", 0).coerceIn(0, 3600),
                )
            },
            snoozeEnabled = o.optBoolean("snoozeEnabled", d.snoozeEnabled),
            snoozeMinutes = o.optInt("snoozeMinutes", d.snoozeMinutes).coerceIn(1, 120),
            snoozeMaxCount = o.optInt("snoozeMaxCount", d.snoozeMaxCount).coerceIn(1, 20),
            isShabbatMode = o.optBoolean("isShabbatMode", d.isShabbatMode),
            reminderText = if (o.has("reminderText")) o.optString("reminderText") else null,
            vibrationMode = enumOr(o.optString("vibrationMode"), d.vibrationMode),
            vibrationOnlySeconds = o.optInt("vibrationOnlySeconds", d.vibrationOnlySeconds).coerceIn(1, 600),
            crescendoEnabled = o.optBoolean("crescendoEnabled", d.crescendoEnabled),
            crescendoStartVolume = o.optInt("crescendoStartVolume", d.crescendoStartVolume).coerceIn(0, 100),
            crescendoStepSeconds = o.optInt("crescendoStepSeconds", d.crescendoStepSeconds).coerceIn(1, 600),
            crescendoStepPercent = o.optInt("crescendoStepPercent", d.crescendoStepPercent).coerceIn(1, 100),
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
