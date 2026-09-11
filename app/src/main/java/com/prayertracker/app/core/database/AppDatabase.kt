package com.prayertracker.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.prayertracker.app.core.database.dao.PrayerRecordDao
import com.prayertracker.app.core.database.dao.QadhaRecordDao
import com.prayertracker.app.core.database.dao.ReminderLogDao
import com.prayertracker.app.core.database.entity.PrayerRecordEntity
import com.prayertracker.app.core.database.entity.QadhaRecordEntity
import com.prayertracker.app.core.database.entity.ReminderLogEntity

@Database(
    entities = [
        PrayerRecordEntity::class,
        QadhaRecordEntity::class,
        ReminderLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun prayerRecordDao(): PrayerRecordDao
    abstract fun qadhaRecordDao(): QadhaRecordDao
    abstract fun reminderLogDao(): ReminderLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "prayer_tracker.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
