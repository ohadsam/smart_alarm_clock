package com.smartring.app.data.repository
import com.smartring.app.data.db.*
import com.smartring.app.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmRepository @Inject constructor(
    private val dao: AlarmDao,
    private val syncDao: SyncDao,
) {

    // ── Alarms ────────────────────────────────────────────────────
    /** Every alarm, including the records this phone keeps on behalf of a child. The list
     *  needs those; anything that arms, counts or displays *rings here* uses
     *  [observeLocalAlarms] instead. */
    fun observeAlarms(): Flow<List<Alarm>> =
        dao.observeAllAlarms().map { it.map { a -> a.toDomain() } }

    /** Only the alarms that actually ring on this phone — what the widgets show. */
    fun observeLocalAlarms(): Flow<List<Alarm>> =
        dao.observeLocalAlarms().map { it.map { a -> a.toDomain() } }

    /** Alarms this phone keeps for its children (never rung here), for the Devices screen. */
    fun observeManagedForChildren(): Flow<List<Alarm>> =
        dao.observeManagedForChildren().map { it.map { a -> a.toDomain() } }

    suspend fun getAlarm(id: Long): Alarm? =
        dao.getAlarmWithDetails(id)?.toDomain()

    /** Every alarm, enabled or not. Used by the widgets, which must be able to offer
     *  switching a disabled alarm back on. */
    suspend fun getAllAlarms(): List<Alarm> =
        dao.getAllAlarmsWithDetails().map { it.toDomain() }

    suspend fun getActiveAlarms(): List<Alarm> =
        dao.getActiveAlarms().map { it.toDomain() }

    /**
     * The active alarms the bulk controls actually act on: [getActiveAlarms] minus the ones a
     * parent locked. The "turn everything off" callers read a list, cancel *those*
     * schedules, and then rely on the DAO to disable them — and the DAO skips locked rows.
     * Cancelling a locked alarm's schedule from the unfiltered list would leave it enabled in
     * the database and silent on the phone, which is the one state nobody can explain.
     */
    suspend fun getActiveControllableAlarms(): List<Alarm> =
        getActiveAlarms().filterNot { it.isLockedForUser }

    /**
     * Whether the alarm is locked against this phone's user — read from the stored row.
     *
     * UI paths call this before acting so they can also *not* do the follow-on work (a
     * toggle that was refused must not go on to cancel the alarm's schedule), and the
     * mutators below check it again themselves, so a caller that forgets still cannot
     * change a locked alarm.
     */
    suspend fun isUserLocked(id: Long): Boolean = dao.isRemoteManaged(id) == true

    /**
     * The user-facing save: the editor, undo, the widget's re-enable, quick actions.
     *
     * Three rules beyond writing the row:
     *  - **A locked alarm is not overwritten.** The stored row is checked, not the caller's
     *    copy, which may predate the lock.
     *  - **A record kept for a child gets a new revision on every save**, and a sharing
     *    identity the first time. That revision is what lets the child tell a newer version
     *    from a replay of an old one.
     *  - **Moving such an alarm away from a child** (to this phone, or to another child)
     *    leaves a tombstone, because the old child still holds a copy nobody else will remove.
     *
     * These steps are separate writes rather than one transaction: a crash between the
     * tombstone and the save leaves a live row beside a pending delete for the same
     * identity, which the sync engine resolves by dropping the delete — the safe direction.
     */
    suspend fun saveAlarm(alarm: Alarm): Long {
        val existing = if (alarm.id != 0L) syncDao.getAlarmEntity(alarm.id) else null
        if (existing?.isRemoteManaged == true) return alarm.id

        var sync = alarm.sync
        val oldTarget = existing?.managedForDeviceId
        val oldSyncId = existing?.syncId
        if (existing != null && oldTarget != null && oldSyncId != null && oldTarget != sync.managedForDeviceId) {
            syncDao.insertTombstone(
                SyncTombstoneEntity(deviceId = oldTarget, syncId = oldSyncId, revision = existing.syncRevision + 1)
            )
            sync = sync.copy(syncId = null, revision = 0L, syncedRevision = 0L)
        }
        val target = sync.managedForDeviceId
        if (target != null) {
            val syncId = sync.syncId ?: newSyncId()
            sync = sync.copy(
                syncId = syncId,
                // max() so an undo (whose held copy predates the delete) still moves forward.
                revision = maxOf(existing?.syncRevision ?: 0L, sync.revision) + 1,
            )
            // Saving again after an undo: the delete this would otherwise race is stale now.
            syncDao.clearTombstonesFor(target, syncId)
        }
        return writeAlarm(alarm.copy(sync = sync))
    }

    /**
     * Writes an alarm on the authority of the paired device that owns it. **Not for UI
     * use** — it deliberately skips the lock, because it is how the lock gets set.
     */
    suspend fun saveRemoteAlarm(alarm: Alarm): Long = writeAlarm(alarm)

    private suspend fun writeAlarm(alarm: Alarm): Long =
        dao.saveAlarmTransaction(
            alarm = alarm.toEntity(),
            rings = alarm.rings.mapIndexed { i, r -> r.copy(orderIndex = i).toEntity(alarm.id) },
            dates = alarm.specificDates.map { it.toEntity(alarm.id) },
        )

    /** A user's delete: refuses a locked alarm, and tombstones a record kept for a child. */
    suspend fun deleteAlarm(id: Long) { syncDao.deleteAlarmForUser(id) }

    /** A delete the owning device asked for. Not for UI use, for the same reason as [saveRemoteAlarm]. */
    suspend fun deleteRemoteAlarm(id: Long) = syncDao.deleteAlarmRow(id)

    /** A user's switch. A locked alarm stays as it is. */
    suspend fun setEnabled(id: Long, enabled: Boolean) {
        if (dao.isRemoteManaged(id) == true) return
        dao.setEnabled(id, enabled)
    }

    /**
     * The system's switch: AlarmFiringService turning a one-time alarm off after it rang.
     * Allowed on a locked alarm on purpose — a parent's one-time alarm that has rung is
     * finished, and refusing here would leave it "armed" in the database forever.
     */
    suspend fun setEnabledBySystem(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)
    suspend fun disableAll()                            = dao.setEnabledAll(false)
    suspend fun freezeAll()                             = dao.setFrozenAll(true)
    suspend fun unfreezeAll()                           = dao.setFrozenAll(false)
    suspend fun enableAll()                             = dao.enableAll()
    suspend fun incrementOccurrences(id: Long)          = dao.incrementOccurrences(id)
    suspend fun snoozeCountSinceLastFire(id: Long): Int = dao.snoozeCountSinceLastFire(id)
    suspend fun lastFiredAt(id: Long): Long?             = dao.lastFiredAt(id)

    // ── Logs / History ────────────────────────────────────────────
    fun observeLogs(): Flow<List<AlarmLog>> =
        dao.observeLogs().map { it.map { l -> l.toDomain() } }

    suspend fun log(alarmId: Long, alarmName: String, scheduledFor: Long, action: String) =
        dao.insertLog(AlarmLogEntity(
            alarmId      = alarmId,
            alarmName    = alarmName,
            scheduledFor = scheduledFor,
            action       = action,
        ))

    suspend fun deleteLog(id: Long)  = dao.deleteLog(id)
    suspend fun deleteAllLogs()      = dao.deleteAllLogs()

    // ── App logs (technical/diagnostic) ─────────────────────────────
    fun observeAppLogs(): Flow<List<AppLogEntry>> =
        dao.observeAppLogs().map { it.map { l -> l.toDomain() } }

    suspend fun insertAppLog(tag: String, message: String) =
        dao.insertAppLog(AppLogEntity(tag = tag, message = message))

    suspend fun deleteAllAppLogs() = dao.deleteAllAppLogs()
    suspend fun deleteAppLogsOlderThan(cutoffMillis: Long) = dao.deleteAppLogsOlderThan(cutoffMillis)

    /** 32 hex chars from a random UUID — 122 random bits, enough that two phones never collide. */
    private fun newSyncId(): String = UUID.randomUUID().toString().replace("-", "")
}
