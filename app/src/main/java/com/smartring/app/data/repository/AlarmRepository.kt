package com.smartring.app.data.repository
import com.smartring.app.data.db.*
import com.smartring.app.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmRepository @Inject constructor(private val dao: AlarmDao) {

    // ── Alarms ────────────────────────────────────────────────────
    fun observeAlarms(): Flow<List<Alarm>> =
        dao.observeAllAlarms().map { it.map { a -> a.toDomain() } }

    suspend fun getAlarm(id: Long): Alarm? =
        dao.getAlarmWithDetails(id)?.toDomain()

    suspend fun getActiveAlarms(): List<Alarm> =
        dao.getActiveAlarms().map { it.toDomain() }

    suspend fun saveAlarm(alarm: Alarm): Long =
        dao.saveAlarmTransaction(
            alarm = alarm.toEntity(),
            rings = alarm.rings.mapIndexed { i, r -> r.copy(orderIndex = i).toEntity(alarm.id) },
            dates = alarm.specificDates.map { it.toEntity(alarm.id) },
        )

    suspend fun deleteAlarm(id: Long)                   = dao.deleteAlarm(id)
    suspend fun setEnabled(id: Long, enabled: Boolean)  = dao.setEnabled(id, enabled)
    suspend fun disableAll()                            = dao.setEnabledAll(false)
    suspend fun freezeAll()                             = dao.setFrozenAll(true)
    suspend fun unfreezeAll()                           = dao.setFrozenAll(false)
    suspend fun enableAll()                             = dao.enableAll()
    suspend fun incrementOccurrences(id: Long)          = dao.incrementOccurrences(id)
    suspend fun snoozeCountSinceLastFire(id: Long): Int = dao.snoozeCountSinceLastFire(id)

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
}
