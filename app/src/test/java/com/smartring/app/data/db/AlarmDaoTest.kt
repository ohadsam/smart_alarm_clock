package com.smartring.app.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * In-memory Room DB via Robolectric — no real device/emulator needed. Covers the
 * DAO/DB-level logic that's easy to silently regress: the insert-vs-update branching
 * in saveAlarmTransaction() (a REPLACE-based upsert here previously cascaded
 * alarm_logs' ON DELETE SET NULL FK and orphaned history on every alarm *edit*), and
 * the raw SQL in lastFiredAt()/snoozeCountSinceLastFire().
 */
@RunWith(RobolectricTestRunner::class)
class AlarmDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: AlarmDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.alarmDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `saveAlarmTransaction inserts a new alarm and returns its generated id`() = runTest {
        val id = dao.saveAlarmTransaction(AlarmEntity(name = "New"), emptyList(), emptyList())
        assertTrue(id > 0)
        assertEquals("New", dao.getAlarmWithDetails(id)?.alarm?.name)
    }

    @Test
    fun `saveAlarmTransaction updates an existing alarm in place without orphaning its history`() = runTest {
        val id = dao.saveAlarmTransaction(AlarmEntity(name = "Original"), emptyList(), emptyList())
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "Original", firedAt = 1L, action = "FIRED"))

        dao.saveAlarmTransaction(AlarmEntity(id = id, name = "Renamed"), emptyList(), emptyList())

        assertEquals("Renamed", dao.getAlarmWithDetails(id)?.alarm?.name)
        // A REPLACE-based upsert (DELETE+INSERT under the hood) would have cascaded
        // alarm_logs' ON DELETE SET NULL FK and orphaned this row on every edit.
        val logs = dao.getAllLogs()
        assertEquals(1, logs.size)
        assertEquals(id, logs.single().alarmId)
    }

    @Test
    fun `saveAlarmTransaction replaces rings on every save instead of appending`() = runTest {
        val id = dao.saveAlarmTransaction(
            AlarmEntity(name = "A"),
            rings = listOf(AlarmRingEntity(alarmId = 0, orderIndex = 0)),
            dates = emptyList(),
        )
        assertEquals(1, dao.getAlarmWithDetails(id)?.rings?.size)

        dao.saveAlarmTransaction(
            AlarmEntity(id = id, name = "A"),
            rings = listOf(
                AlarmRingEntity(alarmId = id, orderIndex = 0),
                AlarmRingEntity(alarmId = id, orderIndex = 1),
            ),
            dates = emptyList(),
        )
        assertEquals(2, dao.getAlarmWithDetails(id)?.rings?.size)
    }

    @Test
    fun `getActiveAlarms only returns enabled, unfrozen alarms`() = runTest {
        val activeId = dao.saveAlarmTransaction(AlarmEntity(name = "Active", isEnabled = true, isFrozen = false), emptyList(), emptyList())
        dao.saveAlarmTransaction(AlarmEntity(name = "Disabled", isEnabled = false, isFrozen = false), emptyList(), emptyList())
        dao.saveAlarmTransaction(AlarmEntity(name = "Frozen", isEnabled = true, isFrozen = true), emptyList(), emptyList())

        val active = dao.getActiveAlarms()
        assertEquals(1, active.size)
        assertEquals(activeId, active.single().alarm.id)
    }

    @Test
    fun `lastFiredAt returns the most recent FIRED timestamp, ignoring other actions`() = runTest {
        val id = dao.saveAlarmTransaction(AlarmEntity(name = "A"), emptyList(), emptyList())
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 1_000L, action = "FIRED"))
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 2_000L, action = "STOPPED"))
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 3_000L, action = "FIRED"))

        assertEquals(3_000L, dao.lastFiredAt(id))
    }

    @Test
    fun `lastFiredAt is null when the alarm has never fired`() = runTest {
        val id = dao.saveAlarmTransaction(AlarmEntity(name = "A"), emptyList(), emptyList())
        assertNull(dao.lastFiredAt(id))
    }

    @Test
    fun `snoozeCountSinceLastFire keeps counting across the snooze's own re-fires`() = runTest {
        // The regression this guards: every snooze re-fire logs its own FIRED row (the
        // ring screen anchors its auto-dismiss timer to that timestamp). Counting
        // snoozes "since the last FIRED" therefore reset to 0 on every single snooze,
        // so snoozeMaxCount was never actually reached and an alarm could be snoozed
        // forever regardless of the configured maximum.
        val id = dao.saveAlarmTransaction(AlarmEntity(name = "A"), emptyList(), emptyList())
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 1_000L, action = "FIRED"))
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 2_000L, action = "SNOOZED"))
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 3_000L, action = "FIRED")) // the snooze re-firing
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 4_000L, action = "SNOOZED"))
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 5_000L, action = "FIRED"))

        assertEquals("both snoozes belong to the same occurrence", 2, dao.snoozeCountSinceLastFire(id))
    }

    @Test
    fun `snoozeCountSinceLastFire resets once the occurrence actually ends`() = runTest {
        val id = dao.saveAlarmTransaction(AlarmEntity(name = "A"), emptyList(), emptyList())
        // Yesterday's occurrence: snoozed twice, then dismissed.
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 1_000L, action = "FIRED"))
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 2_000L, action = "SNOOZED"))
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 3_000L, action = "SNOOZED"))
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 4_000L, action = "STOPPED"))
        assertEquals(0, dao.snoozeCountSinceLastFire(id))

        // Today's occurrence starts with a fresh budget.
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 5_000L, action = "FIRED"))
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 6_000L, action = "SNOOZED"))
        assertEquals(1, dao.snoozeCountSinceLastFire(id))
    }

    @Test
    fun `snoozeCountSinceLastFire also resets after an alarm runs out its ring duration`() = runTest {
        val id = dao.saveAlarmTransaction(AlarmEntity(name = "A"), emptyList(), emptyList())
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 1_000L, action = "FIRED"))
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 2_000L, action = "SNOOZED"))
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 3_000L, action = "MISSED"))

        assertEquals(0, dao.snoozeCountSinceLastFire(id))
    }

    @Test
    fun `deleteAlarm orphans its history rows (SET_NULL) instead of deleting them`() = runTest {
        val id = dao.saveAlarmTransaction(AlarmEntity(name = "A"), emptyList(), emptyList())
        dao.insertLog(AlarmLogEntity(alarmId = id, alarmName = "A", firedAt = 1_000L, action = "FIRED"))

        dao.deleteAlarm(id)

        val logs = dao.getAllLogs()
        assertEquals(1, logs.size)
        assertNull("alarm_logs.alarmId should SET_NULL, not cascade-delete the row", logs.single().alarmId)
    }

    @Test
    fun `deleteAppLogsOlderThan removes only entries before the cutoff`() = runTest {
        dao.insertAppLog(AppLogEntity(timestamp = 1_000L, tag = "t", message = "old"))
        dao.insertAppLog(AppLogEntity(timestamp = 5_000L, tag = "t", message = "new"))

        dao.deleteAppLogsOlderThan(cutoffMillis = 3_000L)

        val remaining = dao.observeAppLogs().first()
        assertEquals(listOf("new"), remaining.map { it.message })
    }
}
