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
    // Multi-device (v4) — see domain/model/AlarmSync.kt for what each one means.
    val syncId: String?                     = null,
    val originDeviceId: String?             = null,
    val originDeviceName: String?           = null,
    val isRemoteManaged: Boolean            = false,
    val managedForDeviceId: String?         = null,
    val syncRevision: Long                  = 0L,
    val syncedRevision: Long                = 0L,
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

// ── Paired devices (v4) ───────────────────────────────────────────

/**
 * Another phone this one has been paired with.
 *
 * [encryptedKey] is the long-term pairing key, wrapped by an Android Keystore key that
 * never leaves the secure hardware — so a copy of this database (a backup, a rooted
 * dump) holds ciphertext that is useless anywhere but on this phone. The plaintext key
 * exists only in memory for the length of a session.
 *
 * Roles are stored from *this* device's point of view: [myRole] is what this phone is to
 * that one, [theirRole] what that one is to this. They are fixed at pairing, after both
 * users confirmed them, and never taken from anything a peer sends later.
 */
@Suppress("ArrayInDataClass")
@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val myRole: String,
    val theirRole: String,
    val bluetoothAddress: String?,
    val encryptedKey: ByteArray,
    val isEnabled: Boolean,
    val createdAt: Long,
    val lastSyncAt: Long?,
    val lastError: String?,
)

/**
 * "Delete this alarm on that child" — kept because the alarm's own row is gone the moment
 * the parent deletes it, and nothing else would remember the child still has a copy.
 * Lives in Room, not a side store, so it commits in the same transaction as the delete.
 */
@Entity(tableName = "sync_tombstones")
data class SyncTombstoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val syncId: String,
    val revision: Long,
)

/** A copy of an alarm that a peer sent, waiting for this device's user to accept it. */
@Suppress("ArrayInDataClass")
@Entity(tableName = "pending_transfers")
data class PendingTransferEntity(
    @PrimaryKey val transferId: String,
    val fromDeviceId: String,
    val fromDeviceName: String,
    val payload: ByteArray,
    val receivedAt: Long,
)

/** A copy of an alarm this device is sending to a peer, waiting for a connection. */
@Suppress("ArrayInDataClass")
@Entity(tableName = "outgoing_transfers")
data class OutgoingTransferEntity(
    @PrimaryKey val transferId: String,
    val toDeviceId: String,
    val payload: ByteArray,
    val createdAt: Long,
)

// ── Relation POJO ─────────────────────────────────────────────────

data class AlarmWithDetails(
    @Embedded val alarm: AlarmEntity,
    @Relation(parentColumn = "id", entityColumn = "alarmId") val rings: List<AlarmRingEntity>,
    @Relation(parentColumn = "id", entityColumn = "alarmId") val dates: List<AlarmDateEntity>,
)
