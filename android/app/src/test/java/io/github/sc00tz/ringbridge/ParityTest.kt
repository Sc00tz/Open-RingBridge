package io.github.sc00tz.ringbridge

import io.github.sc00tz.ringbridge.data.ClockFix
import io.github.sc00tz.ringbridge.data.Derived
import io.github.sc00tz.ringbridge.data.Derived.Activity
import io.github.sc00tz.ringbridge.data.Derived.Sample
import io.github.sc00tz.ringbridge.data.Derived.Segment
import io.github.sc00tz.ringbridge.data.Derived.Session
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.sc00tz.ringbridge.ParityTest.Companion.values

/** tests/parity.json holds what core/derived.py and core/clockfix.py compute from made-up data. These must agree. */
class ParityTest {
    companion object {
        private val p = JSONObject(ParityTest::class.java.classLoader!!.getResource("parity.json")!!.readText())
        private val input = p.getJSONObject("input")
        val sessions = input.getJSONArray("sessions").objects().map { s ->
            Session(s.getLong("start_ts"), s.getLong("end_ts"), s.getString("start_local"), s.getString("end_local"),
                s.getJSONArray("segments").objects().map { Segment(it.getString("stage"), it.getLong("start_ts"), it.getString("start_local"), it.getLong("seconds")) })
        }
        val samples = input.getJSONArray("samples").objects().map { Sample(it.getString("metric"), it.getLong("ts"), it.getString("local"), it.getDouble("value")) }
        val activity = input.getJSONArray("activity").objects().map {
            Activity(it.getLong("start_ts"), it.getLong("end_ts"), it.getString("start_local"), it.getInt("steps"), it.getInt("distance_m"), it.getInt("kcal"))
        }
        val nights = Derived.buildNights(sessions, samples, activity)
        fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
        fun JSONArray.values(): List<Any> = (0 until length()).map { get(it) }
    }

    private fun near(expected: Double, actual: Double?, what: String) = assertEquals(what, expected, actual!!, 1e-6)

    @Test fun nights() {
        val want = p.getJSONArray("nights").objects()
        assertEquals(want.size, nights.size)
        for ((w, n) in want.zip(nights)) {
            assertEquals(w.getString("id"), n.id)
            assertEquals(w.getLong("asleep"), n.asleep)
            assertEquals(w.getLong("in_bed"), n.inBed)
            assertEquals(w.getInt("segments"), n.segments.size)
            assertEquals(w.getJSONObject("seconds").getLong("awake"), n.seconds["awake"])
            assertEquals(w.getInt("steps_during"), n.stepsDuring)
            val vitals = w.getJSONObject("vitals")
            for (key in vitals.keys()) if (key != "samples") near(vitals.getDouble(key), n.vitals[key], "${n.id} $key")
            assertEquals("${n.id} vitals", vitals.keys().asSequence().toSet() - "samples", n.vitals.values.keys)
            if (w.isNull("dip")) assertNull(n.dip) else near(w.getJSONObject("dip").getDouble("dip"), n.dip?.dip, "${n.id} dip")
            assertEquals("${n.id} score", w.getInt("score"), n.score.score)
            assertEquals(w.getString("band"), n.score.band)
            assertEquals("${n.id} confidence", w.getString("confidence"), n.score.confidence)
            assertEquals("${n.id} earned", w.getJSONArray("earned").values().map { (it as Number).toDouble() }, n.score.factors.map { it.earned })
        }
    }

    @Test fun readiness() {
        val want = p.getJSONObject("readiness")
        for (date in want.keys()) {
            val w = want.getJSONObject(date)
            val r = Derived.readiness(nights, activity, date)
            assertEquals(date, w.getBoolean("ready"), r.ready)
            if (!r.ready) continue
            assertEquals("$date score", w.getInt("score"), r.score)
            assertEquals(w.getInt("possible"), r.possible)
            assertEquals(w.getString("band"), r.band)
            assertEquals("$date notes", w.getInt("notes"), r.notes.size)
            assertEquals("$date names", w.getJSONArray("names").values(), r.factors.map { it.name })
            assertEquals("$date earned", w.getJSONArray("earned").values().map { (it as Number).toDouble() }, r.factors.map { it.earned })
        }
    }

    @Test fun consistencyAndRecovery() {
        val c = p.getJSONObject("consistency")
        val got = Derived.consistency(nights)
        assertEquals(c.getBoolean("ready"), got.ready)
        near(c.getDouble("bedtime_spread_min"), got.bedtimeSpreadMin, "bedtime")
        near(c.getDouble("wake_spread_min"), got.wakeSpreadMin, "wake")
        assertEquals(c.getLong("debt_seconds"), got.debtSeconds)
        val r = p.getJSONObject("recovery")
        val rec = Derived.recovery(nights)
        assertEquals(r.getBoolean("ready"), rec.ready)
        if (!rec.ready) return
        assertEquals(r.getString("night"), rec.night)
        near(r.getDouble("resting_hr_usual"), rec.restingHrUsual, "usual hr")
        near(r.getDouble("hrv_usual"), rec.hrvUsual, "usual hrv")
    }

    @Test fun rollup() {
        val want = p.getJSONArray("rollup").objects()
        val got = Derived.dailyRollup(samples, activity, nights)
        assertEquals(want.map { it.getString("date") }, got.map { it["date"] })
        for ((w, g) in want.zip(got)) {
            assertEquals(w.getString("date"), w.keys().asSequence().toSet(), g.keys)
            for (key in w.keys()) {
                if (key == "date") continue
                if (w.isNull(key)) assertNull(g[key]) else near(w.getDouble(key), (g[key] as Number).toDouble(), "${w.getString("date")} $key")
            }
        }
    }

    @Test fun dayMetrics() {
        val want = p.getJSONObject("day_metrics")
        for (date in want.keys()) {
            val got = Derived.dayMetrics(samples.filter { it.local.startsWith(date) }, activity.filter { it.startLocal.startsWith(date) },
                nights.firstOrNull { it.id == date })
            val w = want.getJSONArray(date).objects()
            assertEquals(date, w.map { it.getString("id") }, got.map { it.id })
            for ((a, b) in w.zip(got)) near(a.getDouble("value"), b.value, "$date ${a.getString("id")}")
        }
    }

    @Test fun clockFix() {
        val c = p.getJSONObject("clock")
        val e = c.getJSONObject("event")
        val fix = ClockFix.detect(ClockFix.Event(e.getLong("set_ts"), e.getLong("newest_before")), c.getLong("previous_set_ts"),
            c.getJSONArray("sample_ts").values().map { (it as Number).toLong() }, c.getJSONArray("bin_starts").values().map { (it as Number).toLong() })!!
        val w = c.getJSONObject("fix")
        assertEquals(w.getLong("from_ts"), fix.fromTs)
        assertEquals(w.getLong("to_ts"), fix.toTs)
        assertEquals(w.getLong("offset"), fix.offset)
    }
}
