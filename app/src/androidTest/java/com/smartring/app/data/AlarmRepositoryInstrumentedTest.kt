package com.smartring.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smartring.app.data.db.AlarmLogEntity
import com.smartring.app.data.db.AppDatabase
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmRing
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The repository + Room stack against the device's real SQLite, rather than the
 * JVM-side SQLite Robolectric substitutes. Worth doing on a device because the things
 * that have actually broken here — the REPLACE-upsert that cascaded a foreign key and
 * orphaned every history row, and the snooze-cap query's subselect — are SQL
 * behaviours, not Kotlin ones.
 */
@RunWith(AndroidJUnit4::class)
class AlarmRepositoryInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: AlarmRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = AlarmRepository(db.alarmDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun anAlarmSurvivesASaveAndReloadWithAllOfItsRings() = runTest {
        val id = repository.saveAlarm(
            Alarm(
                name = "בדיקה", hour = 6, minute = 45, ringDurationSeconds = 90,
                snoozeEnabled = true, snoozeMinutes = 7, snoozeMaxCount = 2,
                rings = listOf(
                    AlarmRing(orderIndex = 0, durationSeconds = 30, volumePercent = 40),
                    AlarmRing(orderIndex = 1, durationSeconds = 60, volumePercent = 90),
                ),
            )
        )

        val loaded = repository.getAlarm(id)

        assertNotNull(loaded)
        assertEquals("בדיקה", loaded!!.name)
        assertEquals(6, loaded.hour)
        assertEquals(45, loaded.minute)
        assertEquals(90, loaded.ringDurationSeconds)
        assertEquals(7, loaded.snoozeMinutes)
        assertEquals(2, loaded.rings.size)
        assertEquals(40, loaded.rings[0].volumePercent)
        assertEquals(90, loaded.rings[1].volumePercent)
    }

    @Test
    fun editingAnAlarmKeepsItsRingHistory() = runTest {
        val id = repository.saveAlarm(Alarm(name = "Before"))
        repository.log(id, "Before", System.currentTimeMillis(), "FIRED")

        repository.saveAlarm(repository.getAlarm(id)!!.copy(name = "After"))

        assertEquals("After", repository.getAlarm(id)!!.name)
        assertEquals("editing must not orphan or delete history", 1, db.alarmDao().getAllLogs().size)
    }

    @Test
    fun theSnoozeCapCountsAcrossTheSnoozesOwnReFires() = runTest {
        val id = repository.saveAlarm(Alarm(name = "Snoozy", snoozeEnabled = true, snoozeMaxCount = 2))

        // Straight through the DAO so firedAt (what the query orders by) is explicit —
        // AlarmRepository.log() sets scheduledFor and leaves firedAt at "now".
        logAt(id, 1_000L, "FIRED")
        logAt(id, 2_000L, "SNOOZED")
        logAt(id, 3_000L, "FIRED")   // the snooze re-firing
        logAt(id, 4_000L, "SNOOZED")

        assertEquals(
            "a snooze re-fire must not reset the count, or the cap can never be reached",
            2, repository.snoozeCountSinceLastFire(id),
        )

        logAt(id, 5_000L, "STOPPED")
        assertEquals("dismissing ends the chain", 0, repository.snoozeCountSinceLastFire(id))
    }

    private suspend fun logAt(alarmId: Long, firedAt: Long, action: String) =
        db.alarmDao().insertLog(
            AlarmLogEntity(alarmId = alarmId, alarmName = "A", firedAt = firedAt, action = action)
        )

    @Test
    fun onlyEnabledAndUnfrozenAlarmsCountAsActive() = runTest {
        repository.saveAlarm(Alarm(name = "on"))
        repository.saveAlarm(Alarm(name = "off", isEnabled = false))
        repository.saveAlarm(Alarm(name = "frozen", isFrozen = true))

        val active = repository.getActiveAlarms()

        assertEquals(1, active.size)
        assertEquals("on", active.single().name)
        assertTrue(active.single().isActive)
    }

    @Test
    fun lastFiredAtTracksTheMostRecentRing() = runTest {
        val id = repository.saveAlarm(Alarm(name = "A"))
        logAt(id, 1_000L, "FIRED")
        logAt(id, 9_000L, "FIRED")
        logAt(id, 5_000L, "STOPPED")

        assertEquals(9_000L, repository.lastFiredAt(id))
    }
}
