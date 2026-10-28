package com.smartring.app.data.db
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {

    // ── Alarms ────────────────────────────────────────────────────
    @Transaction @Query("SELECT * FROM alarms ORDER BY hour, minute")
    fun observeAllAlarms(): Flow<List<AlarmWithDetails>>

    /**
     * The alarms that actually ring on *this* phone — every row except the ones this phone
     * only keeps as a record of what a child's phone does. The widgets read this, not
     * [observeAllAlarms]: a widget that counted a child's alarm would show a ring that
     * will never happen here.
     */
    @Transaction @Query("SELECT * FROM alarms WHERE managedForDeviceId IS NULL ORDER BY hour, minute")
    fun observeLocalAlarms(): Flow<List<AlarmWithDetails>>

    @Transaction @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getAlarmWithDetails(id: Long): AlarmWithDetails?

    /** The stored lock bit for one alarm — null when the row does not exist. Read from the
     *  database rather than trusted from a caller's copy, which may be stale. */
    @Query("SELECT isRemoteManaged FROM alarms WHERE id = :id")
    suspend fun isRemoteManaged(id: Long): Boolean?

    /**
     * Every alarm, enabled or not — a one-shot read rather than the Flow.
     *
     * The widgets need it: they used to load `getActiveAlarms()` only, which meant a
     * widget could switch an alarm *off* and then had no way to offer switching it back
     * on, because a disabled alarm was no longer in the list it renders from.
     */
    @Transaction @Query("SELECT * FROM alarms WHERE managedForDeviceId IS NULL ORDER BY hour, minute")
    suspend fun getAllAlarmsWithDetails(): List<AlarmWithDetails>

    // `managedForDeviceId IS NULL`: an alarm this phone only keeps on behalf of a child
    // must never be armed here, and every caller of this query exists to arm, count or
    // cancel alarms that ring on this phone (boot reschedule, the tile, bulk controls).
    @Transaction @Query("SELECT * FROM alarms WHERE isEnabled = 1 AND isFrozen = 0 AND managedForDeviceId IS NULL ORDER BY hour, minute")
    suspend fun getActiveAlarms(): List<AlarmWithDetails>

    @Insert
    suspend fun insertAlarm(a: AlarmEntity): Long

    /** Returns the number of rows changed, which is 0 when the id no longer exists. */
    @Update
    suspend fun updateAlarm(a: AlarmEntity): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRings(r: List<AlarmRingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDates(d: List<AlarmDateEntity>)

    @Query("DELETE FROM alarm_rings WHERE alarmId = :id")
    suspend fun deleteRingsForAlarm(id: Long)

    @Query("DELETE FROM alarm_dates WHERE alarmId = :id")
    suspend fun deleteDatesForAlarm(id: Long)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteAlarm(id: Long)

    // The three bulk controls skip two kinds of row, for two different reasons:
    //  - `isRemoteManaged = 0`: an alarm a parent set is not this user's to switch off, and
    //    "turn everything off" from the list, a widget or the Quick Settings tile is exactly
    //    the one-tap way round that lock.
    //  - `managedForDeviceId IS NULL`: a record of a child's alarm is changed only through
    //    a save that bumps its revision. Flipping `isEnabled` here would change what the
    //    child should do without telling it, and the two phones would disagree for good.
    @Query("UPDATE alarms SET isEnabled = :e WHERE isRemoteManaged = 0 AND managedForDeviceId IS NULL")
    suspend fun setEnabledAll(e: Boolean)

    @Query("UPDATE alarms SET isFrozen = :f WHERE isRemoteManaged = 0 AND managedForDeviceId IS NULL")
    suspend fun setFrozenAll(f: Boolean)

    @Query("UPDATE alarms SET isEnabled = 1, isFrozen = 0 WHERE isRemoteManaged = 0 AND managedForDeviceId IS NULL")
    suspend fun enableAll()

    /**
     * Unconditional — the lock is enforced by [com.smartring.app.data.repository.AlarmRepository],
     * which is the only caller that decides whether a given write is a user's or the system's.
     *
     * For a record kept on behalf of a child the revision is bumped in the same statement, so
     * toggling it through any path still tells the child about the change.
     */
    @Query("""
        UPDATE alarms SET isEnabled = :e,
            syncRevision = syncRevision + CASE WHEN managedForDeviceId IS NOT NULL THEN 1 ELSE 0 END
        WHERE id = :id
    """)
    suspend fun setEnabled(id: Long, e: Boolean)

    @Query("UPDATE alarms SET occurrencesFired = occurrencesFired + 1 WHERE id = :id")
    suspend fun incrementOccurrences(id: Long)

    // ── Logs ──────────────────────────────────────────────────────
    @Insert
    suspend fun insertLog(log: AlarmLogEntity)

    @Query("SELECT * FROM alarm_logs ORDER BY firedAt DESC")
    fun observeLogs(): Flow<List<AlarmLogEntity>>

    @Query("SELECT * FROM alarm_logs ORDER BY firedAt DESC")
    suspend fun getAllLogs(): List<AlarmLogEntity>

    @Query("DELETE FROM alarm_logs WHERE id = :id")
    suspend fun deleteLog(id: Long)

    @Query("DELETE FROM alarm_logs")
    suspend fun deleteAllLogs()

    @Query("SELECT MAX(firedAt) FROM alarm_logs WHERE alarmId = :id AND action = 'FIRED'")
    suspend fun lastFiredAt(id: Long): Long?

    /**
     * Snoozes belonging to the occurrence that is ringing right now, i.e. those logged
     * since this alarm last *finished* — STOPPED (dismissed) or MISSED (ran out its
     * ring duration, or hit the snooze cap).
     *
     * Deliberately not "since the last FIRED row": every snooze re-fire logs its own
     * FIRED row (the ring screen anchors its auto-dismiss timer to that timestamp), so
     * counting from there reset to 0 on every single snooze and snoozeMaxCount was
     * never actually reached — the alarm could be snoozed forever regardless of the
     * configured maximum. A terminal action is the only thing that really ends an
     * occurrence's snooze chain.
     */
    @Query("""
        SELECT COUNT(*) FROM alarm_logs
        WHERE alarmId = :id AND action = 'SNOOZED'
        AND firedAt > (SELECT COALESCE(MAX(firedAt), 0) FROM alarm_logs
                       WHERE alarmId = :id AND action IN ('STOPPED', 'MISSED'))
    """)
    suspend fun snoozeCountSinceLastFire(id: Long): Int

    // ── App logs (technical/diagnostic, separate from alarm_logs history) ──
    @Insert
    suspend fun insertAppLog(log: AppLogEntity)

    @Query("SELECT * FROM app_logs ORDER BY timestamp DESC LIMIT 1000")
    fun observeAppLogs(): Flow<List<AppLogEntity>>

    @Query("DELETE FROM app_logs")
    suspend fun deleteAllAppLogs()

    @Query("DELETE FROM app_logs WHERE timestamp < :cutoffMillis")
    suspend fun deleteAppLogsOlderThan(cutoffMillis: Long)

    // ── Atomic save ───────────────────────────────────────────────
    @Transaction
    suspend fun saveAlarmTransaction(
        alarm: AlarmEntity,
        rings: List<AlarmRingEntity>,
        dates: List<AlarmDateEntity>,
    ): Long {
        // insertAlarm()/updateAlarm() rather than a REPLACE upsert: REPLACE is a SQLite
        // DELETE+INSERT under the hood, which cascades alarm_logs' ON DELETE SET NULL
        // FK and silently orphans every prior history row each time an alarm is edited.
        // Falling back to an insert when the update matches nothing is what makes "undo
        // delete" possible: the row is gone, so @Update changes zero rows and would
        // silently do nothing at all — the alarm would appear to come back in the UI and
        // be absent from the database. Inserting with the original id restores it under
        // that same id, which matters beyond tidiness: the scheduler keys its
        // PendingIntent request codes off the id, so a restore under a fresh id would
        // re-arm a *different* registration and leave the old one to be cancelled by
        // nobody. What it does not bring back is the alarm's history: deleteAlarm has
        // already run alarm_logs' ON DELETE SET NULL, so rows fired before the delete
        // stay orphaned. Undo restores the alarm, not the past.
        val id = if (alarm.id == 0L) insertAlarm(alarm) else {
            if (updateAlarm(alarm) == 0) insertAlarm(alarm) else alarm.id
        }
        deleteRingsForAlarm(id)
        deleteDatesForAlarm(id)
        if (rings.isNotEmpty()) upsertRings(rings.map { it.copy(alarmId = id) })
        if (dates.isNotEmpty()) upsertDates(dates.map { it.copy(alarmId = id) })
        return id
    }
}
