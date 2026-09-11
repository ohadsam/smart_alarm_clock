package com.smartring.app.data.db
import androidx.room.*
import com.smartring.app.domain.model.*

// ── Alarm ─────────────────────────────────────────────────────────

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String                        = "",
    val hour: Int                           = 7,
    val minute: Int                         = 0,
    val specificDateTime: Long?             = null,
    val isEnabled: Boolean                  = true,
    val isFrozen: Boolean                   = false,
    val repeatDaysBitmask: Int              = 0,
    val repeatFrequency: String             = RepeatFrequency.WEEKLY.name,
    // Recurrence end
    val recurrenceEndType: String           = RecurrenceEndType.FOREVER.name,
    val recurrenceUntilDate: Long?          = null,
    val recurrenceCount: Int                = 10,
    val occurrencesFired: Int               = 0,
    // Ring
    val ringDurationSeconds: Int            = 60,
    // Snooze — off by default, matching Alarm.snoozeEnabled's default (v1.4.0)
    val snoozeEnabled: Boolean              = false,
    val snoozeMinutes: Int                  = 10,
    val snoozeMaxCount: Int                 = 3,
    // Shabbat mode
    val isShabbatMode: Boolean              = false,
    // Misc
    val reminderText: String?               = null,
    val vibrationMode: String               = VibrationMode.SOUND_AND_VIBRATION.name,
    val vibrationOnlySeconds: Int           = 10,
    val crescendoEnabled: Boolean           = false,
    val crescendoStartVolume: Int           = 10,
    val crescendoStepSeconds: Int           = 15,
    val crescendoStepPercent: Int           = 10,
)

// ── AlarmRing ─────────────────────────────────────────────────────

@Entity(tableName = "alarm_rings",
    foreignKeys = [ForeignKey(entity = AlarmEntity::class, parentColumns = ["id"],
        childColumns = ["alarmId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("alarmId")])
data class AlarmRingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alarmId: Long,
    val orderIndex: Int          = 0,
    val durationSeconds: Int     = 60,
    val volumePercent: Int       = 100,
    val ringtoneUri: String      = "default",
    val delayAfterSeconds: Int   = 0,
)

// ── AlarmDate ─────────────────────────────────────────────────────

@Entity(tableName = "alarm_dates",
    foreignKeys = [ForeignKey(entity = AlarmEntity::class, parentColumns = ["id"],
        childColumns = ["alarmId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("alarmId")])
data class AlarmDateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alarmId: Long,
    val date: Long,
    val label: String? = null,
)

// ── AlarmLog ──────────────────────────────────────────────────────

@Entity(tableName = "alarm_logs",
    foreignKeys = [ForeignKey(entity = AlarmEntity::class, parentColumns = ["id"],
        childColumns = ["alarmId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("alarmId")])
data class AlarmLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alarmId: Long?,                    // nullable – alarm may be deleted
    val alarmName: String                  = "",
    val firedAt: Long                      = System.currentTimeMillis(),
    val scheduledFor: Long                 = System.currentTimeMillis(),
    val action: String                     = "FIRED",
)

// ── AppLog (technical/diagnostic log, separate from user-facing alarm_logs) ────

@Entity(tableName = "app_logs", indices = [Index("timestamp")])
data class AppLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String      = "",
    val message: String  = "",
)

// ── Relation POJO ─────────────────────────────────────────────────

data class AlarmWithDetails(
    @Embedded val alarm: AlarmEntity,
    @Relation(parentColumn = "id", entityColumn = "alarmId") val rings: List<AlarmRingEntity>,
    @Relation(parentColumn = "id", entityColumn = "alarmId") val dates: List<AlarmDateEntity>,
)
