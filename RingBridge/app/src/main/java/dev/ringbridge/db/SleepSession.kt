package dev.ringbridge.db

import androidx.room.*

@Entity(tableName = "sleep_sessions")
data class SleepSession(
    @PrimaryKey val startMs: Long,
    val endMs: Long,
    val totalSleepMs: Long,
    val deepSleepMs: Long,
    val lightSleepMs: Long,
    val remSleepMs: Long = 0L,   // 0xF3 stages
    val napMs: Long = 0L,         // 0xF5 stages
    val wakeMs: Long,
    val synced: Int = 0,
)

/**
 * Individual sleep stage record — one row per stage transition within a session.
 * Composite primary key on (sessionStartMs, timestamp) prevents duplicate inserts.
 *
 * stageType values (from ring protocol):
 *   0xF1 = Deep   0xF2 = Light   0xF3 = REM   0xF4 = Awake   0xF5 = Nap
 */
@Entity(
    tableName = "sleep_stages",
    primaryKeys = ["sessionStartMs", "timestamp"],
)
data class SleepStage(
    val sessionStartMs: Long,
    val timestamp: Long,      // wall-clock ms when this stage started
    val stageType: Int,
    val durationSec: Long,
)

@Dao
interface SleepSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SleepSession)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<SleepSession>)

    @Query("SELECT * FROM sleep_sessions ORDER BY startMs DESC LIMIT 1")
    suspend fun getLatest(): SleepSession?

    @Query("SELECT * FROM sleep_sessions ORDER BY startMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 7): List<SleepSession>

    @Query("SELECT * FROM sleep_sessions WHERE startMs >= :since ORDER BY startMs ASC")
    suspend fun getSince(since: Long): List<SleepSession>

    @Query("SELECT * FROM sleep_sessions WHERE synced = 0 ORDER BY startMs ASC")
    suspend fun getPending(): List<SleepSession>

    @Query("UPDATE sleep_sessions SET synced = 1 WHERE startMs IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
}

@Dao
interface SleepStageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stages: List<SleepStage>)

    @Query("SELECT * FROM sleep_stages WHERE sessionStartMs = :sessionStartMs ORDER BY timestamp ASC")
    suspend fun getForSession(sessionStartMs: Long): List<SleepStage>
}
