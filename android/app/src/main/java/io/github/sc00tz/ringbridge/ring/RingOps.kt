package io.github.sc00tz.ringbridge.ring

import android.content.Context
import android.util.Base64
import io.github.sc00tz.ringbridge.data.Derived
import io.github.sc00tz.ringbridge.data.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What the app can do with the ring. Ports of bench/ringlink.py's sync, measure and workout. */
object RingOps {
    data class Measure(val selector: Int, val label: String, val seconds: Int, val read: (Int, ByteArray) -> Map<String, Double>?)

    /** Selectors from the sweep in PROTOCOL.md. */
    val MEASURES = linkedMapOf(
        "heart" to Measure(0, "Heart rate", 20) { c, b -> if (c == 0x0601 && b.isNotEmpty()) mapOf("heart_rate" to u(b, 0)) else null },
        "blood" to Measure(1, "Blood pressure", 20) { c, b -> if (c == 0x0603 && b.size >= 2) mapOf("bp_systolic" to u(b, 0), "bp_diastolic" to u(b, 1)) else null },
        "spo2" to Measure(2, "Blood oxygen", 65) { c, b -> if (c == 0x0602 && b.isNotEmpty()) mapOf("spo2" to u(b, 0)) else null },
        "glucose" to Measure(5, "Glucose", 20) { c, b -> if (c == 0x060A && b.size > 20) mapOf("glucose" to u(b, 20) / 10) else null },
        "hrv" to Measure(10, "HRV", 20) { c, b -> if (c == 0x0603 && b.size > 3) mapOf("hrv" to u(b, 3)) else null },
        "stress" to Measure(12, "Stress", 12) { c, b -> if (c == 0x0610 && b.size > 4) mapOf("stress" to u(b, 4)) else null },
    )

    /** Sport codes are unconfirmed (see PROTOCOL.md). A wrong code only mislabels the ring's own record. */
    val WORKOUTS = linkedMapOf("walk" to ("Walk" to 8), "run" to ("Run" to 1), "bike" to ("Bike" to 3))
    const val HEART_STORE_SECONDS = 10

    private fun u(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF).toDouble()

    data class SyncResult(val added: Map<String, Int>, val failed: List<String>, val newestBeforeTs: Long?, val clockProposals: Int)

    private suspend fun <T> withRing(context: Context, address: String, block: suspend (RingLink) -> T): T = RingLink.busy.withLock {
        val link = RingLink(context, address)
        try {
            link.connect()
            block(link)
        } finally {
            link.close()
        }
    }

    /** Read every history stream, back up the raw bytes, import, and queue an upload for the hub. */
    suspend fun sync(context: Context, store: Store, address: String, progress: (String) -> Unit = {}): SyncResult {
        progress("Connecting")
        var setTs = 0L
        var info: Codec.DeviceInfo? = null
        val streams = linkedMapOf<String, RingLink.Stream>()
        withRing(context, address) { link ->
            info = link.status()  // this sets the ring's clock
            setTs = Store.now()
            info?.let(store::setDevice)
            for ((id, code) in store.openRingWorkouts()) {  // a workout the connection dropped before it could be ended
                link.ask(0x030C, byteArrayOf(0, code.toByte()))
                store.markRingClosed(id)
            }
            for (name in Codec.HISTORY_ORDER) {
                progress("Reading $name")
                streams[name] = link.readHistory(name)
                delay(500)
            }
        }
        progress("Importing")
        val folder = File(context.filesDir, "raw/${Store.toLocal(setTs).replace(" ", "_").replace(":", "")}").apply { mkdirs() }
        streams.forEach { (name, s) -> File(folder, "$name.bin").writeBytes(s.data) }
        val result = importSync(store, setTs, streams.mapValues { it.value.data to it.value.verified })
        // The hub decodes and checks the same bytes itself.
        val upload = JSONObject().put("set_ts", setTs).put("streams", JSONObject().apply {
            streams.forEach { (name, s) ->
                put(name, JSONObject().put("data", Base64.encodeToString(s.data, Base64.NO_WRAP)).put("crc", s.crc ?: Codec.crc16(s.data)))
            }
        })
        info?.let { upload.put("device", JSONObject().put("device_id", it.deviceId).put("firmware", it.firmware).put("battery_state", it.batteryState)
            .put("battery", it.battery).put("charging", it.charging)) }
        store.enqueue("/api/v1/sync", upload)
        return result
    }

    /** A port of core/importer.py. A stream failing its checksum isn't imported. */
    fun importSync(store: Store, setTs: Long, streams: Map<String, Pair<ByteArray, Boolean>>): SyncResult {
        val added = linkedMapOf<String, Int>()
        val failed = mutableListOf<String>()
        var newest: Long? = null
        for (name in Codec.HISTORY_ORDER) {
            val (data, verified) = streams[name] ?: continue
            if (!verified) { failed += name; continue }
            val decoded = Codec.decodeHistory(name, data)
            // Labels as the ring stamped them, before any correction: what the clock check needs.
            decoded.samples.map { Store.toTs(it.local) }.filter { it < setTs }.maxOrNull()?.let { newest = maxOf(newest ?: 0, it) }
            added[name] = store.addDecoded(decoded)
        }
        store.recordClockSet(setTs, newest)
        val proposals = store.checkClock()
        store.logSync(if (failed.isEmpty()) "ok" else "failed-checksum", JSONObject().put("added", JSONObject(added as Map<*, *>))
            .put("failed", JSONArray(failed)).put("newest_before_ts", newest).put("clock_proposals", proposals))
        return SyncResult(added, failed, newest, proposals)
    }

    /** Run one measurement and store its last non-zero values. */
    suspend fun measure(context: Context, store: Store, address: String, kind: String, onValue: (Map<String, Double>) -> Unit,
                        cancelled: () -> Boolean): Map<String, Double> {
        val spec = MEASURES.getValue(kind)
        var values: Map<String, Double> = emptyMap()
        var finished = false
        withRing(context, address) { link ->
            for (t in 1..5) { link.send(0x0309, byteArrayOf(1, t.toByte(), 2)); delay(200) }
            link.send(0x032F, byteArrayOf(1, spec.selector.toByte()))
            val started = System.currentTimeMillis()
            var accepted = false
            try {
                while (System.currentTimeMillis() - started < (spec.seconds + 45) * 1000L && !cancelled()) {
                    val f = link.recv(1_000) ?: continue
                    if (f.code == 0x032F && !accepted) {
                        if (f.payload.firstOrNull()?.toInt() != 0) throw RingError("The ring refused. It needs to be on your finger and off the charger.")
                        accepted = true
                    } else if (f.code == 0x040E) {
                        // [selector, 01] is success. Other statuses (02 seen) end it with no reading.
                        if (f.payload.size >= 2 && f.payload[1].toInt() != 1) {
                            throw RingError("The ring stopped the measurement early (status ${"%02x".format(f.payload[1])}). Check the fit, keep still and try again.")
                        }
                        finished = true
                        break
                    } else {
                        val got = spec.read(f.code, f.payload)
                        if (got != null && got.values.all { it != 0.0 }) { values = got; onValue(got) }
                    }
                }
            } finally {
                runCatching {
                    link.send(0x032F, byteArrayOf(0, spec.selector.toByte()))
                    for (t in 1..5) { link.send(0x0309, byteArrayOf(0, t.toByte(), 2)); delay(100) }
                }
            }
        }
        if (cancelled()) return emptyMap()
        if (values.isEmpty()) throw RingError(if (finished) "The ring finished without a reading. Check the fit and try again." else "No reading before the time limit. Check the fit and try again.")
        val local = Store.nowLocal()
        values.forEach { (metric, value) -> store.addLive(metric, value, local) }
        store.enqueue("/api/v1/readings", JSONObject().put("samples", JSONArray(values.map { (m, v) ->
            JSONObject().put("metric", m).put("local", local).put("value", v).put("origin", "live") })))
        return values
    }

    data class WorkoutUpdate(val message: String, val heart: Int? = null, val totals: Derived.WorkoutTotals? = null, val points: List<Pair<Long, Int>> = emptyList())

    /**
     * Run one workout until stopped, keeping every raw frame so unknown fields can be decoded later.
     * If the connection drops, it keeps trying to reconnect. Runs inside WorkoutService.
     */
    suspend fun workout(context: Context, store: Store, address: String, kind: String, update: (WorkoutUpdate) -> Unit, stopped: () -> Boolean): Long =
        RingLink.busy.withLock {
            val code = WORKOUTS.getValue(kind).second
            var link = RingLink(context, address)
            link.connect()
            val reply = link.ask(0x030C, byteArrayOf(1, code.toByte()))
            if (reply == null || reply.firstOrNull()?.toInt() != 0) {
                link.close()
                throw RingError(if (reply != null) "The ring didn't start the workout. It needs to be on your finger and off the charger." else "The ring didn't answer the workout command.")
            }
            val log = File(context.filesDir, "raw/workout-${Store.nowLocal().replace(" ", "_").replace(":", "")}").apply { mkdirs() }.resolve("frames.log")
            val id = store.startWorkout(kind, code)
            val heart = mutableListOf<Int>()
            val points = mutableListOf<Pair<Long, Int>>()
            var first: IntArray? = null
            var last: IntArray? = null
            var lastStored = 0L
            var endedByRing = false
            val samples = JSONArray()
            update(WorkoutUpdate("Recording"))
            try {
                log.bufferedWriter().use { out ->
                    link.setLive(true)
                    while (!stopped()) {
                        if (!link.connected) {
                            update(WorkoutUpdate("Connection lost, reconnecting", heart.lastOrNull(), Derived.workoutTotals(first, last, heart), points.toList()))
                            link.close()
                            link = RingLink(context, address)
                            try { link.connect(); link.setLive(true); update(WorkoutUpdate("Recording")) } catch (e: RingError) { delay(3_000) }
                            continue
                        }
                        val f = link.recv(1_000) ?: continue
                        out.write("${System.currentTimeMillis() / 1000.0} ${"%04x".format(f.code)} ${f.payload.joinToString("") { "%02x".format(it) }}\n")
                        out.flush()
                        when {
                            f.code == 0x0601 && f.payload.isNotEmpty() && f.payload[0].toInt() != 0 -> {
                                val bpm = f.payload[0].toInt() and 0xFF
                                heart += bpm
                                if (System.currentTimeMillis() / 1000 - lastStored >= HEART_STORE_SECONDS) {
                                    lastStored = System.currentTimeMillis() / 1000
                                    val local = Store.nowLocal()
                                    store.addLive("heart_rate", bpm.toDouble(), local, "workout")
                                    samples.put(JSONObject().put("metric", "heart_rate").put("local", local).put("value", bpm).put("origin", "workout"))
                                    points += Store.toTs(local) to bpm
                                }
                            }
                            f.code == 0x0600 && f.payload.size >= 6 -> {
                                val p = f.payload
                                last = IntArray(3) { (p[it * 2].toInt() and 0xFF) or ((p[it * 2 + 1].toInt() and 0xFF) shl 8) }
                                if (first == null) first = last
                            }
                            f.code == 0x040C && f.payload.firstOrNull()?.toInt() == 0 -> { endedByRing = true; break }
                        }
                        update(WorkoutUpdate("Recording", heart.lastOrNull(), Derived.workoutTotals(first, last, heart), points.toList()))
                    }
                }
            } finally {
                var closed = endedByRing
                runCatching {
                    if (!link.connected) { link.close(); link = RingLink(context, address); link.connect() }
                    link.send(0x030C, byteArrayOf(0, code.toByte()))
                    closed = true
                    link.setLive(false)
                }
                link.close()
                val totals = Derived.workoutTotals(first, last, heart)
                store.finishWorkout(id, totals, if (closed) "done" else "lost", !closed)
                store.workout(id)?.let { w ->
                    store.enqueue("/api/v1/readings", JSONObject().put("samples", samples).put("workouts", JSONArray().put(JSONObject()
                        .put("kind", kind).put("ring_code", code).put("start_local", w.startLocal).put("end_local", w.endLocal)
                        .put("steps", w.steps).put("distance_m", w.distanceM).put("kcal", w.kcal).put("hr_avg", w.hrAvg)
                        .put("hr_min", w.hrMin).put("hr_max", w.hrMax).put("status", w.status))))
                }
            }
            id
        }
}
