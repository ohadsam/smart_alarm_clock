package com.smartring.app.di
import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.smartring.app.data.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add new columns to alarms table
        db.execSQL("ALTER TABLE alarms ADD COLUMN specificDateTime INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE alarms ADD COLUMN recurrenceEndType TEXT NOT NULL DEFAULT 'FOREVER'")
        db.execSQL("ALTER TABLE alarms ADD COLUMN recurrenceUntilDate INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE alarms ADD COLUMN recurrenceCount INTEGER NOT NULL DEFAULT 10")
        db.execSQL("ALTER TABLE alarms ADD COLUMN occurrencesFired INTEGER NOT NULL DEFAULT 0")
        // Update alarm_logs table
        db.execSQL("ALTER TABLE alarm_logs ADD COLUMN alarmName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE alarm_logs ADD COLUMN scheduledFor INTEGER NOT NULL DEFAULT 0")
        // Change action column default
        db.execSQL("UPDATE alarm_logs SET action = 'FIRED' WHERE action = ''")
        // Recreate alarm_logs with nullable alarmId
        db.execSQL("""
            CREATE TABLE alarm_logs_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                alarmId INTEGER DEFAULT NULL,
                alarmName TEXT NOT NULL DEFAULT '',
                firedAt INTEGER NOT NULL DEFAULT 0,
                scheduledFor INTEGER NOT NULL DEFAULT 0,
                action TEXT NOT NULL DEFAULT 'FIRED',
                FOREIGN KEY(alarmId) REFERENCES alarms(id) ON DELETE SET NULL
            )
        """)
        db.execSQL("INSERT INTO alarm_logs_new SELECT id, alarmId, alarmName, firedAt, scheduledFor, action FROM alarm_logs")
        db.execSQL("DROP TABLE alarm_logs")
        db.execSQL("ALTER TABLE alarm_logs_new RENAME TO alarm_logs")
        db.execSQL("CREATE INDEX index_alarm_logs_alarmId ON alarm_logs(alarmId)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE alarms ADD COLUMN snoozeEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE alarms ADD COLUMN isShabbatMode INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS app_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestamp INTEGER NOT NULL,
                tag TEXT NOT NULL,
                message TEXT NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_app_logs_timestamp ON app_logs(timestamp)")
    }
}

/**
 * Multi-device sync (v4). Every added alarm column has a default, so every existing alarm
 * comes through as an ordinary local one — not locked, not shared, revision 0 — which is
 * exactly what it was before the feature existed.
 *
 * The four new tables are created empty with no foreign keys, so none of this can touch
 * existing data. The column lists mirror the entities exactly; Room validates the result
 * against them on open and throws if a type, nullability or primary key differs.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE alarms ADD COLUMN syncId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE alarms ADD COLUMN originDeviceId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE alarms ADD COLUMN originDeviceName TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE alarms ADD COLUMN isRemoteManaged INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE alarms ADD COLUMN managedForDeviceId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE alarms ADD COLUMN syncRevision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE alarms ADD COLUMN syncedRevision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS paired_devices (
                deviceId TEXT NOT NULL,
                name TEXT NOT NULL,
                myRole TEXT NOT NULL,
                theirRole TEXT NOT NULL,
                bluetoothAddress TEXT,
                encryptedKey BLOB NOT NULL,
                isEnabled INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                lastSyncAt INTEGER,
                lastError TEXT,
                PRIMARY KEY(deviceId)
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_tombstones (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                deviceId TEXT NOT NULL,
                syncId TEXT NOT NULL,
                revision INTEGER NOT NULL
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pending_transfers (
                transferId TEXT NOT NULL,
                fromDeviceId TEXT NOT NULL,
                fromDeviceName TEXT NOT NULL,
                payload BLOB NOT NULL,
                receivedAt INTEGER NOT NULL,
                PRIMARY KEY(transferId)
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS outgoing_transfers (
                transferId TEXT NOT NULL,
                toDeviceId TEXT NOT NULL,
                payload BLOB NOT NULL,
                createdAt INTEGER NOT NULL,
                PRIMARY KEY(transferId)
            )
        """)
    }
}

@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "smartring.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

    @Provides fun provideAlarmDao(db: AppDatabase) = db.alarmDao()
    @Provides fun provideSyncDao(db: AppDatabase) = db.syncDao()
}
