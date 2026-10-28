package com.smartring.app.data.db
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AlarmEntity::class,
        AlarmRingEntity::class,
        AlarmDateEntity::class,
        AlarmLogEntity::class,
        AppLogEntity::class,
        PairedDeviceEntity::class,
        SyncTombstoneEntity::class,
        PendingTransferEntity::class,
        OutgoingTransferEntity::class,
    ],
    version = 4,        // bumped 3 → 4: multi-device sync columns + paired_devices & friends
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun syncDao(): SyncDao
}
