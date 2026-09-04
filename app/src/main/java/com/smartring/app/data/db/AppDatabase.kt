package com.smartring.app.data.db
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AlarmEntity::class,
        AlarmRingEntity::class,
        AlarmDateEntity::class,
        AlarmLogEntity::class,
    ],
    version = 2,        // bumped from 1 → 2 for new columns
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
}
