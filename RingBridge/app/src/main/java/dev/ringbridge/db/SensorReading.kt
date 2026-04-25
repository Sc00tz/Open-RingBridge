package dev.ringbridge.db

import androidx.room.*

/** A single timestamped sensor observation from the ring. */
@Entity(tableName = "sensor_readings")
data class SensorReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Unix epoch millis — always set from the phone clock at receive time. */
    val timestamp: Long,
    /**
     * Sensor type key. Matches the server API type field, e.g.:
     *   hr, spo2, systolic, diastolic, steps, distance_m,
     *   calories, hrv, stress, resp_rate, battery
     */
    val type: String,
    val value: Double,
    /** 0 = not yet synced to HA, 1 = synced. */
    val synced: Int = 0,
)

@Dao
interface SensorReadingDao {

    @Insert
    suspend fun insert(reading: SensorReading): Long

    @Insert
    suspend fun insertAll(readings: List<SensorReading>)

    /** Up to [limit] readings not yet sent to HA, oldest first. Capped to avoid CursorWindow OOM. */
    @Query("SELECT * FROM sensor_readings WHERE synced = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getPending(limit: Int = 200): List<SensorReading>

    @Query("UPDATE sensor_readings SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    /** Latest N readings across all types — used for the status UI. */
    @Query("SELECT * FROM sensor_readings ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<SensorReading>

    /** Latest reading for a specific type — used for the status card. */
    @Query("SELECT * FROM sensor_readings WHERE type = :type ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(type: String): SensorReading?

    /** History for a specific sensor type after [since] ms epoch, ordered oldest-first. */
    @Query("SELECT * FROM sensor_readings WHERE type = :type AND timestamp >= :since ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getHistory(type: String, since: Long, limit: Int = 5000): List<SensorReading>

    /** Minimum value for a sensor type within a time window — used for resting HR. */
    @Query("SELECT MIN(value) FROM sensor_readings WHERE type = :type AND timestamp >= :since")
    suspend fun getMin(type: String, since: Long): Double?

    /** Prune readings older than [cutoff] ms that have already been synced. */
    @Query("DELETE FROM sensor_readings WHERE timestamp < :cutoff AND synced = 1")
    suspend fun pruneOld(cutoff: Long)

    /** Abandon unsynced readings older than [cutoff] — they'll never reach the server. */
    @Query("DELETE FROM sensor_readings WHERE timestamp < :cutoff AND synced = 0")
    suspend fun pruneStaleUnsynced(cutoff: Long)
}
