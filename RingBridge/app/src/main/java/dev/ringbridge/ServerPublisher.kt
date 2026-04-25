package dev.ringbridge

import android.content.Context
import android.util.Log
import dev.ringbridge.db.RingDatabase
import dev.ringbridge.db.SensorReading
import dev.ringbridge.db.SleepSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sends sensor readings and sleep sessions to the RingBridge server.
 *
 * Strategy:
 *  - Every reading is written to SQLite first (never lost).
 *  - We then attempt an immediate batch flush to the server.
 *  - Readings/sessions that fail to send stay as synced=0 in the DB.
 *  - [flush] is called whenever connectivity is restored to drain the backlog.
 */
class ServerPublisher(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val db  get() = RingDatabase.get(context)
    private val dao get() = db.readings()

    private var lastFlushMs = 0L
    private val flushMutex  = Mutex()

    // ── Public API ────────────────────────────────────────────────────────────

    /** Persist [reading] then flush if the configured sync interval has elapsed. */
    suspend fun publish(reading: SensorReading) {
        dao.insert(reading)
        val intervalMs = Settings.syncIntervalMs(context)
        val now = System.currentTimeMillis()
        if (intervalMs == 0L || now - lastFlushMs >= intervalMs) {
            flush(Settings.wifiOnly(context))
            lastFlushMs = now
        }
    }

    /** Convenience: build a [SensorReading] and publish it. */
    suspend fun publish(type: String, value: Double) {
        publish(SensorReading(timestamp = System.currentTimeMillis(), type = type, value = value))
    }

    /**
     * POST all unsynced readings and sleep sessions to the server.
     * Marks them synced on success. Safe to call at any time — concurrent
     * callers skip rather than pile up (the in-flight flush already covers them).
     */
    suspend fun flush(wifiOnly: Boolean = false) = withContext(Dispatchers.IO) {
        if (!Settings.isConfigured(context)) return@withContext

        val network = NetworkMonitor(context)
        if (!network.isUsable(wifiOnly)) return@withContext

        // Only one flush at a time — extra callers return immediately rather than
        // each loading their own cursor window of the same rows.
        if (!flushMutex.tryLock()) return@withContext
        try {
            // Always prune before flushing — clears any giant accumulated backlog before
            // we try to load it all into memory.
            prune()
            flushReadings()
            flushSleepSessions()
        } finally {
            flushMutex.unlock()
        }
    }

    /** Remove old readings to keep the DB lean. */
    suspend fun prune(days: Int = 30) {
        val cutoff        = System.currentTimeMillis() - days * 86_400_000L
        val staleCutoff   = System.currentTimeMillis() - 7 * 86_400_000L   // 7 days
        dao.pruneOld(cutoff)
        dao.pruneStaleUnsynced(staleCutoff)   // abandon backlog that's too old to matter
        Log.d(TAG, "Pruned sensor readings older than $days days (abandoned unsynced > 7d)")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun flushReadings() {
        val url   = Settings.serverUrl(context).trimEnd('/') + "/api/v1/readings"
        val token = Settings.deviceToken(context)

        // Loop: each getPending() call returns at most BATCH_SIZE rows (LIMIT in the query),
        // so the SQLite CursorWindow never overflows. We keep looping until the backlog
        // is empty or a POST fails (retry on next flush cycle).
        var totalFlushed = 0
        while (true) {
            val batch = dao.getPending(BATCH_SIZE)
            if (batch.isEmpty()) break

            if (totalFlushed == 0) Log.d(TAG, "Flushing readings to server…")

            val body = JSONObject().apply {
                put("readings", JSONArray().apply {
                    for (r in batch) {
                        put(JSONObject().apply {
                            put("timestamp", r.timestamp)
                            put("type",      r.type)
                            put("value",     r.value)
                        })
                    }
                })
            }.toString()

            val ok = postJson(url, token, body)
            if (ok) {
                dao.markSynced(batch.map { it.id })
                totalFlushed += batch.size
            } else {
                break  // stop on first failure; retry next flush cycle
            }
        }
        if (totalFlushed > 0) Log.d(TAG, "Flushed $totalFlushed readings OK")
    }

    private suspend fun flushSleepSessions() {
        val sleepDao = db.sleepSessions()
        val pending  = sleepDao.getPending()
        if (pending.isEmpty()) return

        Log.d(TAG, "Flushing ${pending.size} sleep sessions to server…")

        val url   = Settings.serverUrl(context).trimEnd('/') + "/api/v1/sleep"
        val token = Settings.deviceToken(context)

        val sessionsArray = JSONArray().apply {
            for (s in pending) {
                put(JSONObject().apply {
                    put("start_ms",       s.startMs)
                    put("end_ms",         s.endMs)
                    put("total_sleep_ms", s.totalSleepMs)
                    put("deep_sleep_ms",  s.deepSleepMs)
                    put("light_sleep_ms", s.lightSleepMs)
                    put("rem_sleep_ms",   s.remSleepMs)
                    put("nap_ms",         s.napMs)
                    put("wake_ms",        s.wakeMs)
                })
            }
        }
        val body = JSONObject().apply { put("sessions", sessionsArray) }.toString()

        val ok = postJson(url, token, body)
        if (ok) {
            sleepDao.markSynced(pending.map { it.startMs })
            Log.d(TAG, "Flushed ${pending.size} sleep sessions OK")
        }
    }

    private fun postJson(url: String, token: String, body: String): Boolean = try {
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        Log.w(TAG, "Server POST failed: ${e.message}")
        false
    }

    companion object {
        private const val TAG        = "ServerPublisher"
        private const val BATCH_SIZE = 200   // readings per HTTP POST — keeps JSON payload ~10KB
    }
}
