package io.github.sc00tz.ringbridge.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.sc00tz.ringbridge.ring.Codec
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The phone's database. Same tables and columns as core/store.py, so the hub and the bench read the same shapes.
 * Times are stored twice: `local` is the wall clock the ring recorded, `ts` the matching Unix time.
 */
class Store(context: Context) : SQLiteOpenHelper(context, "ring.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    companion object {
        const val SAME_READING_SECONDS = 60
        private val SCHEMA = """
            CREATE TABLE sample (source TEXT NOT NULL, metric TEXT NOT NULL, ts INTEGER NOT NULL, local TEXT NOT NULL,
              value REAL NOT NULL, origin TEXT NOT NULL, PRIMARY KEY (source, metric, ts));
            CREATE INDEX sample_local ON sample (local);
            CREATE TABLE activity (source TEXT NOT NULL, start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL, start_local TEXT NOT NULL,
              steps INTEGER NOT NULL, distance_m INTEGER NOT NULL, kcal INTEGER NOT NULL, PRIMARY KEY (source, start_ts));
            CREATE TABLE sleep_session (source TEXT NOT NULL, start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL,
              start_local TEXT NOT NULL, end_local TEXT NOT NULL, PRIMARY KEY (source, start_ts));
            CREATE TABLE sleep_segment (source TEXT NOT NULL, session_start_ts INTEGER NOT NULL, start_ts INTEGER NOT NULL,
              start_local TEXT NOT NULL, stage TEXT NOT NULL, seconds INTEGER NOT NULL, PRIMARY KEY (source, start_ts));
            CREATE TABLE workout (id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, kind TEXT, ring_code INTEGER,
              start_ts INTEGER NOT NULL, end_ts INTEGER, start_local TEXT NOT NULL, end_local TEXT,
              steps INTEGER, distance_m INTEGER, kcal INTEGER, hr_avg INTEGER, hr_min INTEGER, hr_max INTEGER,
              status TEXT NOT NULL, ring_open INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE device_status (source TEXT PRIMARY KEY, updated_ts INTEGER NOT NULL, json TEXT NOT NULL);
            CREATE TABLE sync_log (ts INTEGER NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL);
            CREATE TABLE clock_set (source TEXT NOT NULL, set_ts INTEGER NOT NULL, newest_before INTEGER, PRIMARY KEY (source, set_ts));
            CREATE TABLE clock_fix (id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, event_ts INTEGER NOT NULL,
              from_ts INTEGER NOT NULL, to_ts INTEGER NOT NULL, offset INTEGER NOT NULL, status TEXT NOT NULL, basis TEXT NOT NULL, decided_ts INTEGER);
            CREATE TABLE upload (id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL, body TEXT NOT NULL, created_ts INTEGER NOT NULL)
        """.trimIndent()

        fun toTs(local: String): Long = LocalDateTime.parse(local.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toEpochSecond()
        fun toLocal(ts: Long): String = LocalDateTime.ofInstant(Instant.ofEpochSecond(ts), ZoneId.systemDefault()).format(Codec.LOCAL)
        fun now() = System.currentTimeMillis() / 1000
        fun nowLocal(): String = LocalDateTime.now().withNano(0).format(Codec.LOCAL)
    }

    private val db get() = writableDatabase
    private fun <T> Cursor.all(read: (Cursor) -> T): List<T> = use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
    private fun Cursor.longOrNull(i: Int) = if (isNull(i)) null else getLong(i)
    private fun Cursor.intOrNull(i: Int) = if (isNull(i)) null else getInt(i)
    private fun q(sql: String, vararg args: Any?) = db.rawQuery(sql, args.map { it?.toString() }.toTypedArray())

    @Synchronized fun isEmpty() = q("SELECT 1 FROM sample LIMIT 1").use { !it.moveToFirst() }

    // ----- importing -----
    /** Import one decoded history stream. Safe to repeat, since nothing is deleted from the ring. */
    @Synchronized fun addDecoded(raw: Codec.Decoded, source: String = "ring"): Int {
        var added = 0
        // The ring keeps sending records it stamped with a wrong clock until they age out, so fix them on every import.
        val decoded = shift(raw, clockFixes(source, "applied"))
        db.beginTransaction()
        try {
            for (s in decoded.samples) {
                val ts = toTs(s.local)
                // The stored record replaces the live reading of the same measurement.
                db.execSQL("DELETE FROM sample WHERE source=? AND metric=? AND origin='live' AND ts BETWEEN ? AND ?", arrayOf(source, s.metric, ts - 120, ts + 120))
                val nearby = q("SELECT 1 FROM sample WHERE source=? AND metric=? AND ts BETWEEN ? AND ? LIMIT 1",
                    source, s.metric, ts - SAME_READING_SECONDS, ts + SAME_READING_SECONDS).use { it.moveToFirst() }
                if (nearby) continue
                db.execSQL("INSERT INTO sample VALUES (?,?,?,?,?,'history')", arrayOf(source, s.metric, ts, s.local, s.value))
                added++
            }
            // A half-hour period still in progress grows between reads, so the newest read wins.
            for (a in decoded.activity) {
                db.execSQL("INSERT OR REPLACE INTO activity VALUES (?,?,?,?,?,?,?)",
                    arrayOf(source, toTs(a.start), toTs(a.end), a.start, a.steps, a.distanceM, a.kcal))
            }
            for (s in decoded.sleep) {
                val start = toTs(s.start)
                if (q("SELECT 1 FROM sleep_session WHERE source=? AND start_ts=?", source, start).use { !it.moveToFirst() }) added++
                db.execSQL("INSERT OR REPLACE INTO sleep_session VALUES (?,?,?,?,?)", arrayOf(source, start, toTs(s.end), s.start, s.end))
                for (g in s.segments) {
                    db.execSQL("INSERT OR REPLACE INTO sleep_segment VALUES (?,?,?,?,?,?)", arrayOf(source, start, toTs(g.start), g.start, g.stage, g.seconds))
                }
            }
            for (w in decoded.workouts) added += mergeRingWorkout(w, source)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    private fun shift(d: Codec.Decoded, fixes: List<ClockFix.StoredFix>): Codec.Decoded {
        if (fixes.isEmpty()) return d
        fun move(local: String): String {
            val off = ClockFix.offsetFor(toTs(local), fixes)
            return if (off != 0L) toLocal(toTs(local) + off) else local
        }
        fun by(local: String, other: String): Pair<String, String> {
            val off = ClockFix.offsetFor(toTs(local), fixes)
            return if (off != 0L) toLocal(toTs(local) + off) to toLocal(toTs(other) + off) else local to other
        }
        return Codec.Decoded(
            d.samples.map { it.copy(local = move(it.local)) },
            d.activity.map { val (s, e) = by(it.start, it.end); it.copy(start = s, end = e) },
            d.sleep.map { s ->
                val off = ClockFix.offsetFor(toTs(s.start), fixes)
                if (off == 0L) s else s.copy(start = toLocal(toTs(s.start) + off), end = toLocal(toTs(s.end) + off),
                    segments = s.segments.map { it.copy(start = toLocal(toTs(it.start) + off)) })
            },
            d.workouts.map { val (s, e) = by(it.start, it.end); it.copy(start = s, end = e) },
        )
    }

    @Synchronized fun addLive(metric: String, value: Double, local: String = nowLocal(), origin: String = "live", source: String = "ring") {
        db.execSQL("INSERT OR REPLACE INTO sample VALUES (?,?,?,?,?,?)", arrayOf(source, metric, toTs(local), local, value, origin))
    }

    // ----- reading -----
    @Synchronized fun samples(date: String? = null, startTs: Long? = null, endTs: Long? = null, source: String = "ring"): List<Derived.Sample> {
        val args = mutableListOf<Any?>(source)
        var sql = "SELECT metric, ts, local, value, origin FROM sample WHERE source=?"
        if (date != null) { sql += " AND local LIKE ?"; args += "$date%" }
        if (startTs != null) { sql += " AND ts >= ? AND ts <= ?"; args += startTs; args += endTs }
        return q("$sql ORDER BY ts", *args.toTypedArray()).all { Derived.Sample(it.getString(0), it.getLong(1), it.getString(2), it.getDouble(3), it.getString(4)) }
    }

    @Synchronized fun activity(date: String? = null, source: String = "ring"): List<Derived.Activity> {
        val (sql, args) = if (date != null) "AND start_local LIKE ?" to arrayOf<Any?>(source, "$date%") else "" to arrayOf<Any?>(source)
        return q("SELECT start_ts, end_ts, start_local, steps, distance_m, kcal FROM activity WHERE source=? $sql ORDER BY start_ts", *args)
            .all { Derived.Activity(it.getLong(0), it.getLong(1), it.getString(2), it.getInt(3), it.getInt(4), it.getInt(5)) }
    }

    @Synchronized fun sleep(source: String = "ring"): List<Derived.Session> {
        val segments = q("SELECT session_start_ts, start_ts, start_local, stage, seconds FROM sleep_segment WHERE source=? ORDER BY start_ts", source)
            .all { it.getLong(0) to Derived.Segment(it.getString(3), it.getLong(1), it.getString(2), it.getLong(4)) }.groupBy({ it.first }, { it.second })
        return q("SELECT start_ts, end_ts, start_local, end_local FROM sleep_session WHERE source=? ORDER BY start_ts", source)
            .all { Derived.Session(it.getLong(0), it.getLong(1), it.getString(2), it.getString(3), segments[it.getLong(0)].orEmpty()) }
    }

    @Synchronized fun days(source: String = "ring"): List<String> = q(
        "SELECT DISTINCT substr(local,1,10) d FROM sample WHERE source=? UNION SELECT DISTINCT substr(start_local,1,10) FROM activity WHERE source=? " +
            "UNION SELECT DISTINCT substr(end_local,1,10) FROM sleep_session WHERE source=? ORDER BY d", source, source, source).all { it.getString(0) }

    // ----- workouts -----
    data class Workout(
        val id: Long, val kind: String?, val ringCode: Int?, val startTs: Long, val endTs: Long?, val startLocal: String, val endLocal: String?,
        val steps: Int?, val distanceM: Int?, val kcal: Int?, val hrAvg: Int?, val hrMin: Int?, val hrMax: Int?, val status: String,
    )

    @Synchronized fun startWorkout(kind: String, ringCode: Int, source: String = "ring"): Long {
        val local = nowLocal()
        return db.insert("workout", null, ContentValues().apply {
            put("source", source); put("kind", kind); put("ring_code", ringCode); put("start_ts", toTs(local)); put("start_local", local)
            put("status", "recording"); put("ring_open", 1)
        })
    }

    @Synchronized fun finishWorkout(id: Long, t: Derived.WorkoutTotals, status: String, ringOpen: Boolean) {
        val local = nowLocal()
        db.execSQL("UPDATE workout SET end_ts=?, end_local=?, steps=?, distance_m=?, kcal=?, hr_avg=?, hr_min=?, hr_max=?, status=?, ring_open=? WHERE id=?",
            arrayOf(toTs(local), local, t.steps, t.distanceM, t.kcal, t.hrAvg, t.hrMin, t.hrMax, status, if (ringOpen) 1 else 0, id))
    }

    /** Attach the ring's own record to our workout overlapping it, else add it. Returns 1 if new. */
    private fun mergeRingWorkout(w: Codec.RingWorkout, source: String): Int {
        val start = toTs(w.start)
        val end = toTs(w.end)
        val hr = { v: Int -> if (v != 0) v else null }
        val id = q("SELECT id FROM workout WHERE source=? AND start_ts <= ? AND COALESCE(end_ts, start_ts) >= ? ORDER BY start_ts LIMIT 1", source, end, start)
            .use { if (it.moveToFirst()) it.getLong(0) else null }
        if (id != null) {
            db.execSQL("UPDATE workout SET steps=?, distance_m=?, kcal=?, hr_avg=COALESCE(?,hr_avg), hr_min=COALESCE(?,hr_min), hr_max=COALESCE(?,hr_max) WHERE id=?",
                arrayOf(w.steps, w.distanceM, w.kcal, hr(w.hrAvg), hr(w.hrMin), hr(w.hrMax), id))
            return 0
        }
        db.execSQL("INSERT INTO workout (source, ring_code, start_ts, end_ts, start_local, end_local, steps, distance_m, kcal, hr_avg, hr_min, hr_max, status) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'ring')", arrayOf(source, w.ringCode, start, end, w.start, w.end, w.steps, w.distanceM, w.kcal, hr(w.hrAvg), hr(w.hrMin), hr(w.hrMax)))
        return 1
    }

    /** A workout still "recording" at startup means the app stopped mid-way. The ring may still be in workout mode. */
    @Synchronized fun closeInterruptedWorkouts() {
        q("SELECT id, start_ts, start_local FROM workout WHERE status='recording'").all { Triple(it.getLong(0), it.getLong(1), it.getString(2)) }.forEach { (id, startTs, startLocal) ->
            val last = q("SELECT ts, local FROM sample WHERE origin='workout' AND ts >= ? ORDER BY ts DESC LIMIT 1", startTs)
                .use { if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null }
            db.execSQL("UPDATE workout SET status='lost', end_ts=?, end_local=? WHERE id=?", arrayOf(last?.first ?: startTs, last?.second ?: startLocal, id))
        }
    }

    @Synchronized fun openRingWorkouts(source: String = "ring") =
        q("SELECT id, ring_code FROM workout WHERE source=? AND ring_open=1 AND status!='recording'", source).all { it.getLong(0) to it.getInt(1) }

    @Synchronized fun markRingClosed(id: Long) = db.execSQL("UPDATE workout SET ring_open=0 WHERE id=?", arrayOf(id))

    @Synchronized fun workouts(date: String? = null, limit: Int = 30, source: String = "ring"): List<Workout> {
        val (sql, args) = if (date != null) "AND start_local LIKE ?" to arrayOf<Any?>(source, "$date%", limit) else "" to arrayOf<Any?>(source, limit)
        return q("SELECT id, kind, ring_code, start_ts, end_ts, start_local, end_local, steps, distance_m, kcal, hr_avg, hr_min, hr_max, status " +
            "FROM workout WHERE source=? AND status!='recording' $sql ORDER BY start_ts DESC LIMIT ?", *args).all {
            Workout(it.getLong(0), it.getString(1), it.intOrNull(2), it.getLong(3), it.longOrNull(4), it.getString(5), it.getString(6),
                it.intOrNull(7), it.intOrNull(8), it.intOrNull(9), it.intOrNull(10), it.intOrNull(11), it.intOrNull(12), it.getString(13))
        }
    }

    @Synchronized fun workout(id: Long) = workouts(limit = 10_000).firstOrNull { it.id == id }

    // ----- device and sync log -----
    @Synchronized fun setDevice(info: Codec.DeviceInfo, source: String = "ring") {
        val json = JSONObject().put("device_id", info.deviceId).put("firmware", info.firmware).put("battery_state", info.batteryState)
            .put("battery", info.battery).put("charging", info.charging)
        db.execSQL("INSERT OR REPLACE INTO device_status VALUES (?,?,?)", arrayOf(source, now(), json.toString()))
    }

    @Synchronized fun device(source: String = "ring"): Pair<Long, JSONObject>? =
        q("SELECT updated_ts, json FROM device_status WHERE source=?", source).use { if (it.moveToFirst()) it.getLong(0) to JSONObject(it.getString(1)) else null }

    @Synchronized fun logSync(status: String, detail: JSONObject) = db.execSQL("INSERT INTO sync_log VALUES (?,?,?)", arrayOf(now(), status, detail.toString()))

    @Synchronized fun lastSync(): Pair<Long, JSONObject>? =
        q("SELECT ts, detail FROM sync_log ORDER BY ts DESC LIMIT 1").use { if (it.moveToFirst()) it.getLong(0) to JSONObject(it.getString(1)) else null }

    // ----- ring clock (see ClockFix) -----
    @Synchronized fun recordClockSet(setTs: Long, newestBefore: Long?, source: String = "ring") =
        db.execSQL("INSERT OR REPLACE INTO clock_set VALUES (?,?,?)", arrayOf(source, setTs, newestBefore))

    /** Look for a clock that was behind at any sync not yet checked. Returns how many new proposals. */
    @Synchronized fun checkClock(source: String = "ring"): Int {
        val events = q("SELECT set_ts, newest_before FROM clock_set WHERE source=? ORDER BY set_ts", source).all { ClockFix.Event(it.getLong(0), it.longOrNull(1)) }
        val seen = q("SELECT event_ts FROM clock_fix WHERE source=?", source).all { it.getLong(0) }.toSet()
        val sampleTs = q("SELECT DISTINCT ts FROM sample WHERE source=? AND origin='history' ORDER BY ts", source).all { it.getLong(0) }
        val binStarts = q("SELECT start_ts FROM activity WHERE source=?", source).all { it.getLong(0) }
        var found = 0
        events.forEachIndexed { i, event ->
            if (event.setTs in seen) return@forEachIndexed
            val fix = ClockFix.detect(event, if (i > 0) events[i - 1].setTs else null, sampleTs, binStarts) ?: return@forEachIndexed
            val basis = JSONObject().put("set_ts", event.setTs).put("newest_before", event.newestBefore).put("first_after", fix.firstAfter)
            db.execSQL("INSERT INTO clock_fix (source, event_ts, from_ts, to_ts, offset, status, basis) VALUES (?,?,?,?,?,'proposed',?)",
                arrayOf(source, event.setTs, fix.fromTs, fix.toTs, fix.offset, basis.toString()))
            found++
        }
        return found
    }

    @Synchronized fun clockFixes(source: String = "ring", status: String? = null): List<ClockFix.StoredFix> {
        val (sql, args) = if (status != null) " AND status=?" to arrayOf<Any?>(source, status) else "" to arrayOf<Any?>(source)
        return q("SELECT id, event_ts, from_ts, to_ts, offset, status FROM clock_fix WHERE source=?$sql ORDER BY event_ts", *args)
            .all { ClockFix.StoredFix(it.getLong(0), it.getLong(1), it.getLong(2), it.getLong(3), it.getLong(4), it.getString(5)) }
    }

    /** action: apply, undo or dismiss. Only ring history moves; readings this app took kept their own clock. */
    @Synchronized fun decideClockFix(id: Long, action: String) {
        val fix = clockFixes().firstOrNull { it.id == id } ?: throw IllegalArgumentException("No such correction")
        val (from, to) = mapOf("apply" to ("proposed" to "applied"), "undo" to ("applied" to "undone"), "dismiss" to ("proposed" to "dismissed")).getValue(action)
        require(fix.status == from) { "This correction is ${fix.status}." }
        db.beginTransaction()
        try {
            when (action) {
                "apply" -> move(fix.fromTs, fix.toTs, fix.offset)
                "undo" -> move(fix.fromTs + fix.offset, fix.toTs + fix.offset, -fix.offset)
            }
            db.execSQL("UPDATE clock_fix SET status=?, decided_ts=? WHERE id=?", arrayOf(to, now(), id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun move(low: Long, high: Long, delta: Long, source: String = "ring") {
        val rows = q("SELECT metric, ts, value FROM sample WHERE source=? AND origin='history' AND ts BETWEEN ? AND ?", source, low, high)
            .all { Triple(it.getString(0), it.getLong(1), it.getDouble(2)) }
        db.execSQL("DELETE FROM sample WHERE source=? AND origin='history' AND ts BETWEEN ? AND ?", arrayOf(source, low, high))
        rows.forEach { (m, ts, v) -> db.execSQL("INSERT OR IGNORE INTO sample VALUES (?,?,?,?,?,'history')", arrayOf(source, m, ts + delta, toLocal(ts + delta), v)) }
        val bins = q("SELECT start_ts, end_ts, steps, distance_m, kcal FROM activity WHERE source=? AND start_ts BETWEEN ? AND ?", source, low, high)
            .all { longArrayOf(it.getLong(0), it.getLong(1), it.getLong(2), it.getLong(3), it.getLong(4)) }
        db.execSQL("DELETE FROM activity WHERE source=? AND start_ts BETWEEN ? AND ?", arrayOf(source, low, high))
        bins.forEach { b -> db.execSQL("INSERT OR REPLACE INTO activity VALUES (?,?,?,?,?,?,?)", arrayOf(source, b[0] + delta, b[1] + delta, toLocal(b[0] + delta), b[2], b[3], b[4])) }
        val sessions = q("SELECT start_ts, end_ts FROM sleep_session WHERE source=? AND start_ts BETWEEN ? AND ?", source, low, high).all { it.getLong(0) to it.getLong(1) }
        for ((start, end) in sessions) {
            val segs = q("SELECT start_ts, stage, seconds FROM sleep_segment WHERE source=? AND session_start_ts=?", source, start).all { Triple(it.getLong(0), it.getString(1), it.getLong(2)) }
            db.execSQL("DELETE FROM sleep_segment WHERE source=? AND session_start_ts=?", arrayOf(source, start))
            db.execSQL("DELETE FROM sleep_session WHERE source=? AND start_ts=?", arrayOf(source, start))
            db.execSQL("INSERT OR REPLACE INTO sleep_session VALUES (?,?,?,?,?)", arrayOf(source, start + delta, end + delta, toLocal(start + delta), toLocal(end + delta)))
            segs.forEach { (ts, stage, sec) -> db.execSQL("INSERT OR REPLACE INTO sleep_segment VALUES (?,?,?,?,?,?)", arrayOf(source, start + delta, ts + delta, toLocal(ts + delta), stage, sec)) }
        }
        q("SELECT id, start_ts, end_ts FROM workout WHERE source=? AND status='ring' AND start_ts BETWEEN ? AND ?", source, low, high)
            .all { Triple(it.getLong(0), it.getLong(1), it.getLong(2)) }.forEach { (id, s, e) ->
                db.execSQL("UPDATE workout SET start_ts=?, end_ts=?, start_local=?, end_local=? WHERE id=?", arrayOf(s + delta, e + delta, toLocal(s + delta), toLocal(e + delta), id))
            }
    }

    // ----- hub upload queue: kept until the hub accepts it -----
    @Synchronized fun enqueue(path: String, body: JSONObject) = db.execSQL("INSERT INTO upload (path, body, created_ts) VALUES (?,?,?)", arrayOf(path, body.toString(), now()))
    @Synchronized fun pending() = q("SELECT id, path, body FROM upload ORDER BY id").all { Triple(it.getLong(0), it.getString(1), it.getString(2)) }
    @Synchronized fun uploaded(id: Long) = db.execSQL("DELETE FROM upload WHERE id=?", arrayOf(id))
    @Synchronized fun pendingCount() = q("SELECT COUNT(*) FROM upload").use { it.moveToFirst(); it.getInt(0) }
}
