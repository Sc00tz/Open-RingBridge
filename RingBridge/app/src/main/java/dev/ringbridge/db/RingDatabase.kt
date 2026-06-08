package dev.ringbridge.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SensorReading::class, SleepSession::class, SleepStage::class], version = 5, exportSchema = false)
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

        // Adds a UNIQUE(timestamp, type) index so re-pulled history dedupes via
        // OnConflictStrategy.IGNORE (the ring is no longer told to delete its history).
        // Existing duplicate rows must be removed first, else the UNIQUE index fails.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "DELETE FROM sensor_readings WHERE id NOT IN " +
                    "(SELECT MIN(id) FROM sensor_readings GROUP BY timestamp, type)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_sensor_readings_timestamp_type` ON `sensor_readings` (`timestamp`, `type`)"
                )
            }
        }

        fun get(context: Context): RingDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                RingDatabase::class.java,
                "ring.db"
            )
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
            // NOTE: destructive fallback intentionally NOT enabled. A missing migration
            // path will throw at startup rather than silently wiping the user's health
            // history. Any future schema change MUST ship a corresponding Migration.
            // (No pre-release builds shipped versions 1–3 publicly, so only 3→4 exists.)
            .build().also { INSTANCE = it }
        }
    }
}
