package com.smartring.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.smartring.app.di.MIGRATION_1_2
import com.smartring.app.di.MIGRATION_2_3
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
import java.io.File

/**
 * The migrations had no coverage at all — neither suite had ever executed one. That is
 * the worst gap this app could have: a bad migration doesn't degrade a feature, it
 * throws on the very first `Room.databaseBuilder().build()` after an update, so *every*
 * upgrading user's app dies on launch and the only way out is uninstalling, which
 * deletes all their alarms. The committed keystore exists precisely so people install
 * updates over the top — which is exactly the path that runs these.
 *
 * Room's own MigrationTestHelper needs the exported schema JSON for every old version,
 * and `app/schemas/` is generated at build time and never committed, so instead each
 * old schema is written out by hand as raw SQL at its real version number. Opening it
 * afterwards through Room is what does the asserting: Room finds the version gap, runs
 * the real migration objects, then validates the resulting schema against the entity
 * definitions and throws IllegalStateException("Migration didn't properly handle…") on
 * any mismatch — the identical code path a user's device takes.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val dbName = "migration-test.db"
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = deleteDb()
    @After fun tearDown() = deleteDb()

    private fun deleteDb() {
        listOf("", "-wal", "-shm").forEach { suffix ->
            File(context.getDatabasePath(dbName).path + suffix).delete()
        }
    }

    // ── Old schemas, written out as those versions actually shipped ──────────

    /** alarm_rings / alarm_dates are untouched by both migrations, so they are
     *  identical in every version. */
    private fun SupportSQLiteDatabase.createUnchangedChildTables() {
        execSQL("""
            CREATE TABLE IF NOT EXISTS alarm_rings (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                alarmId INTEGER NOT NULL,
                orderIndex INTEGER NOT NULL,
                durationSeconds INTEGER NOT NULL,
                volumePercent INTEGER NOT NULL,
                ringtoneUri TEXT NOT NULL,
                delayAfterSeconds INTEGER NOT NULL,
                FOREIGN KEY(alarmId) REFERENCES alarms(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """)
        execSQL("CREATE INDEX IF NOT EXISTS index_alarm_rings_alarmId ON alarm_rings(alarmId)")
        execSQL("""
            CREATE TABLE IF NOT EXISTS alarm_dates (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                alarmId INTEGER NOT NULL,
                date INTEGER NOT NULL,
                label TEXT,
                FOREIGN KEY(alarmId) REFERENCES alarms(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """)
        execSQL("CREATE INDEX IF NOT EXISTS index_alarm_dates_alarmId ON alarm_dates(alarmId)")
    }

    /** Version 1: before specificDateTime/recurrence existed, and while alarm_logs
     *  still had a NOT NULL alarmId and no alarmName/scheduledFor. */
    private fun createV1(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS alarms (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                hour INTEGER NOT NULL,
                minute INTEGER NOT NULL,
                isEnabled INTEGER NOT NULL,
                isFrozen INTEGER NOT NULL,
                repeatDaysBitmask INTEGER NOT NULL,
                repeatFrequency TEXT NOT NULL,
                ringDurationSeconds INTEGER NOT NULL,
                snoozeMinutes INTEGER NOT NULL,
                snoozeMaxCount INTEGER NOT NULL,
                reminderText TEXT,
                vibrationMode TEXT NOT NULL,
                vibrationOnlySeconds INTEGER NOT NULL,
                crescendoEnabled INTEGER NOT NULL,
                crescendoStartVolume INTEGER NOT NULL,
                crescendoStepSeconds INTEGER NOT NULL,
                crescendoStepPercent INTEGER NOT NULL
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS alarm_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                alarmId INTEGER NOT NULL,
                firedAt INTEGER NOT NULL,
                action TEXT NOT NULL,
                FOREIGN KEY(alarmId) REFERENCES alarms(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """)
        db.createUnchangedChildTables()
    }

    private val v1Alarm = """
        INSERT INTO alarms (name, hour, minute, isEnabled, isFrozen, repeatDaysBitmask,
            repeatFrequency, ringDurationSeconds, snoozeMinutes, snoozeMaxCount,
            reminderText, vibrationMode, vibrationOnlySeconds, crescendoEnabled,
            crescendoStartVolume, crescendoStepSeconds, crescendoStepPercent)
        VALUES ('Old alarm', 6, 45, 1, 0, 31, 'WEEKLY', 90, 7, 2,
            'wake up', 'SOUND_AND_VIBRATION', 10, 0, 10, 15, 10)
    """

    /**
     * Writes an old-version database to disk and closes it, leaving `PRAGMA
     * user_version` at [version] so Room sees a real upgrade to perform.
     */
    private fun seedOldDatabase(schemaVersion: Int, seed: SupportSQLiteDatabase.() -> Unit) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(schemaVersion) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.apply {
            seed()
            version = schemaVersion
        }
        helper.close()
    }

    /**
     * Opens the seeded database the way the app does. Room runs whatever migrations the
     * version gap calls for and then validates the result against the entities; a
     * migration that leaves a column, type, nullability or index wrong throws here.
     */
    private fun openThroughRoom(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

    // ── Tests ───────────────────────────────────────────────────────────────

    @Test
    fun `Room accepts the schema after upgrading a v2 database`() = runTest {
        seedOldDatabase(2) { createV1(this); MIGRATION_1_2.migrate(this) }

        val room = openThroughRoom()
        // Forces the open (and therefore the migration + validation) to actually happen
        // rather than staying lazy.
        room.alarmDao().getActiveAlarms()
        room.close()
    }

    @Test
    fun `Room accepts the schema after upgrading a v1 database`() = runTest {
        seedOldDatabase(1) { createV1(this) }

        val room = openThroughRoom()
        room.alarmDao().getActiveAlarms()
        room.close()
    }

    @Test
    fun `an alarm created before the upgrade survives v1 to v3 with sane defaults`() = runTest {
        seedOldDatabase(1) { createV1(this); execSQL(v1Alarm) }

        val room = openThroughRoom()
        val alarm = room.alarmDao().getActiveAlarms().single().alarm
        // The user's own settings must come through untouched…
        assertEquals("Old alarm", alarm.name)
        assertEquals(6, alarm.hour)
        assertEquals(45, alarm.minute)
        assertEquals(31, alarm.repeatDaysBitmask)
        assertEquals("wake up", alarm.reminderText)
        // …and every column added along the way must land on a usable value, not a null
        // that breaks the enum mapping or a recurrence that reads as already expired.
        assertEquals("FOREVER", alarm.recurrenceEndType)
        assertEquals(0, alarm.occurrencesFired)
        assertNull(alarm.specificDateTime)
        // snoozeEnabled defaults to 1 for pre-existing alarms deliberately: snooze was
        // on for everyone before v1.4.0 made it opt-in for *new* alarms, and an update
        // must not silently remove it from an alarm someone already relies on.
        assertTrue(alarm.snoozeEnabled)
        room.close()
    }

    @Test
    fun `ring history survives the alarm_logs rebuild in v1 to v2`() = runTest {
        // MIGRATION_1_2 rebuilds alarm_logs from scratch to make alarmId nullable, so
        // deleting an alarm keeps its history instead of cascading it away. A mistake in
        // that copy step would silently wipe every upgrading user's entire history.
        seedOldDatabase(1) {
            createV1(this)
            execSQL(v1Alarm)
            execSQL("INSERT INTO alarm_logs (alarmId, firedAt, action) VALUES (1, 1700000000000, 'STOPPED')")
        }

        val room = openThroughRoom()
        val logs = room.alarmDao().getAllLogs()
        assertEquals(1, logs.size)
        assertEquals("STOPPED", logs.single().action)
        assertEquals(1700000000000L, logs.single().firedAt)
        assertEquals(1L, logs.single().alarmId!!)
        room.close()
    }

    @Test
    fun `deleting an alarm after the upgrade keeps its history rows`() = runTest {
        // The reason alarm_logs was rebuilt at all: its FK is ON DELETE SET NULL, so
        // history outlives the alarm. Worth pinning end-to-end through a real migration,
        // since getting the rebuilt FK wrong would restore the old cascade silently.
        seedOldDatabase(1) {
            createV1(this)
            execSQL(v1Alarm)
            execSQL("INSERT INTO alarm_logs (alarmId, firedAt, action) VALUES (1, 1700000000000, 'STOPPED')")
        }

        val room = openThroughRoom()
        room.alarmDao().deleteAlarm(1L)
        val logs = room.alarmDao().getAllLogs()
        assertEquals("history must outlive the alarm it belonged to", 1, logs.size)
        assertNull(logs.single().alarmId)
        room.close()
    }

    @Test
    fun `the app_logs table added in v2 to v3 is usable`() = runTest {
        seedOldDatabase(2) { createV1(this); MIGRATION_1_2.migrate(this) }

        val room = openThroughRoom()
        room.alarmDao().insertAppLog(AppLogEntity(tag = "T", message = "m"))
        assertEquals(1, room.alarmDao().observeAppLogs().first().size)
        room.close()
    }
}
