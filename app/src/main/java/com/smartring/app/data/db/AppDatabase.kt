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
    ],
    version = 3,        // bumped 2 → 3: snoozeEnabled/isShabbatMode + app_logs table
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
}
