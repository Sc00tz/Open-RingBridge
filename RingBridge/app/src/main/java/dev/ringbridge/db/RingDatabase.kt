package dev.ringbridge.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SensorReading::class, SleepSession::class, SleepStage::class], version = 4, exportSchema = false)
abstract class RingDatabase : RoomDatabase() {

    abstract fun readings(): SensorReadingDao
    abstract fun sleepSessions(): SleepSessionDao
    abstract fun sleepStages(): SleepStageDao

    companion object {
        @Volatile private var INSTANCE: RingDatabase? = null

        // Adds the sleep_stages table without wiping existing data.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sleep_stages` " +
                    "(`sessionStartMs` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, " +
                    "`stageType` INTEGER NOT NULL, `durationSec` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`sessionStartMs`, `timestamp`))"
                )
            }
        }

        fun get(context: Context): RingDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                RingDatabase::class.java,
                "ring.db"
            )
            .addMigrations(MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build().also { INSTANCE = it }
        }
    }
}
