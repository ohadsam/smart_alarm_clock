package com.smartring.app.data.db
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {

    // ── Alarms ────────────────────────────────────────────────────
    @Transaction @Query("SELECT * FROM alarms ORDER BY hour, minute")
    fun observeAllAlarms(): Flow<List<AlarmWithDetails>>

    @Transaction @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getAlarmWithDetails(id: Long): AlarmWithDetails?

    @Transaction @Query("SELECT * FROM alarms WHERE isEnabled = 1 AND isFrozen = 0")
    suspend fun getActiveAlarms(): List<AlarmWithDetails>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlarm(a: AlarmEntity): Long

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

    @Query("UPDATE alarms SET isEnabled = :e")
    suspend fun setEnabledAll(e: Boolean)

    @Query("UPDATE alarms SET isFrozen = :f")
    suspend fun setFrozenAll(f: Boolean)

    @Query("UPDATE alarms SET isEnabled = 1, isFrozen = 0")
    suspend fun enableAll()

    @Query("UPDATE alarms SET isEnabled = :e WHERE id = :id")
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

    // ── Atomic save ───────────────────────────────────────────────
    @Transaction
    suspend fun saveAlarmTransaction(
        alarm: AlarmEntity,
        rings: List<AlarmRingEntity>,
        dates: List<AlarmDateEntity>,
    ): Long {
        val id = upsertAlarm(alarm)
        deleteRingsForAlarm(id)
        deleteDatesForAlarm(id)
        if (rings.isNotEmpty()) upsertRings(rings.map { it.copy(alarmId = id) })
        if (dates.isNotEmpty()) upsertDates(dates.map { it.copy(alarmId = id) })
        return id
    }
}
