package io.github.sc00tz.ringbridge.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Metrics computed by combining ring data. A port of core/derived.py; tests/parity.json checks both give the same numbers.
 *
 * Nothing here comes from the ring or the official app. Each result says which formula and inputs it used.
 * These are wellness estimates, not medical readings.
 */
object Derived {
    const val NIGHT_GAP_SECONDS = 2 * 3600L
    const val DEFAULT_SLEEP_TARGET_SECONDS = 8 * 3600L
    const val BASELINE_NIGHTS = 5
    const val READINESS_MIN_BASELINE = 3
    const val MANY_STEPS_DURING_SLEEP = 300
    private const val FULL_NIGHT = 4 * 3600L

    /** Score bands as the official app describes them. The score itself is our own formula. */
    private val BANDS = listOf(85 to "Optimal", 70 to "Good", 60 to "Average", 0 to "Poor")

    fun clamp(x: Double, low: Double = 0.0, high: Double = 1.0) = max(low, min(high, x))
    fun band(score: Int) = BANDS.first { score >= it.first }.second

    /** Python's round(): halves go to the even neighbour. Kept so both versions agree to the last digit. */
    fun pyRound(x: Double): Int = BigDecimal(x).setScale(0, RoundingMode.HALF_EVEN).toInt()
    fun pyRound(x: Double, digits: Int): Double = BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()

    fun mean(values: Collection<Double>) = values.sum() / values.size
    fun median(values: Collection<Double>): Double {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
    fun pstdev(values: Collection<Double>): Double {
        val m = mean(values)
        return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
    }
    private fun percentile(values: List<Double>, fraction: Double): Double {
        val ordered = values.sorted()
        return ordered[min(ordered.size - 1, (ordered.size * fraction).toInt())]
    }
    private fun pct(x: Double) = "${pyRound(x * 100)}%"
    private fun f1(x: Double) = String.format("%.1f", x)
    private fun f0(x: Double) = String.format("%.0f", x)

    // ----- shapes -----
    data class Sample(val metric: String, val ts: Long, val local: String, val value: Double, val origin: String = "history")
    data class Activity(val startTs: Long, val endTs: Long, val startLocal: String, val steps: Int, val distanceM: Int, val kcal: Int)
    data class Segment(val stage: String, val startTs: Long, val startLocal: String, val seconds: Long)
    data class Session(val startTs: Long, val endTs: Long, val startLocal: String, val endLocal: String, val segments: List<Segment>)
    data class Factor(val name: String, val weight: Int, val earned: Double, val detail: String)
    data class Score(val score: Int, val band: String, var confidence: String, val notes: MutableList<String>, val factors: List<Factor>)
    data class Dip(val dayAvg: Double, val nightAvg: Double, val dip: Double)

    /** Overnight summaries. `values` holds keys like core/derived.py's: hr_avg, resting_hr, spo2_avg, hrv_avg... */
    data class Vitals(val samples: Map<String, Int>, val values: Map<String, Double>) {
        operator fun get(key: String) = values[key]
        operator fun contains(key: String) = key in values
    }

    data class Night(
        val id: String, val startTs: Long, val endTs: Long, val startLocal: String, val endLocal: String,
        val sessions: Int, val inBed: Long, val asleep: Long, val seconds: Map<String, Long>, val efficiency: Double?,
        val segments: List<Segment>,
    ) {
        var vitals = Vitals(emptyMap(), emptyMap())
        var dip: Dip? = null
        var stepsDuring = 0
        lateinit var score: Score
    }

    // ----- nights -----
    /** The ring can split one night into several sessions, so join sessions under 2 hours apart. A night is named by the date you woke. */
    fun groupNights(sessions: List<Session>): List<Night> {
        val groups = mutableListOf<MutableList<Session>>()
        for (s in sessions.sortedBy { it.startTs }) {
            if (groups.isNotEmpty() && s.startTs - groups.last().last().endTs <= NIGHT_GAP_SECONDS) groups.last() += s
            else groups += mutableListOf(s)
        }
        return groups.map { g ->
            val seconds = linkedMapOf("deep" to 0L, "light" to 0L, "rem" to 0L, "awake" to 0L, "nap" to 0L)
            val segments = mutableListOf<Segment>()
            g.forEachIndexed { i, s ->
                for (seg in s.segments) {
                    if (seg.stage in seconds) seconds[seg.stage] = seconds.getValue(seg.stage) + seg.seconds
                    segments += seg
                }
                // Show the gap between sessions as awake time on the timeline.
                if (i + 1 < g.size) segments += Segment("awake", s.endTs, s.endLocal, g[i + 1].startTs - s.endTs)
            }
            val start = g.first().startTs
            val end = g.last().endTs
            val inBed = end - start
            val asleep = seconds.getValue("deep") + seconds.getValue("light") + seconds.getValue("rem") + seconds.getValue("nap")
            // Gaps between sessions count as awake: the ring recorded no sleep during them.
            seconds["awake"] = max(seconds.getValue("awake"), inBed - asleep)
            Night(g.last().endLocal.substring(0, 10), start, end, g.first().startLocal, g.last().endLocal, g.size, inBed, asleep,
                seconds, if (inBed != 0L) asleep.toDouble() / inBed else null, segments)
        }
    }

    /** Summarize stored readings taken during a night. `samples` must be in time order. */
    fun overnightVitals(night: Night, samples: List<Sample>): Vitals {
        val by = linkedMapOf<String, MutableList<Sample>>()
        for (s in samples) if (s.ts in night.startTs..night.endTs) by.getOrPut(s.metric) { mutableListOf() } += s
        val out = linkedMapOf<String, Double>()
        val hr = by["heart_rate"].orEmpty()
        if (hr.size >= 6) {
            val values = hr.map { it.value }
            out["hr_avg"] = mean(values)
            out["hr_min"] = values.min()
            out["resting_hr"] = percentile(values, 0.10)
            val rolling = (0 until values.size - 2).map { i -> mean(values.subList(i, i + 3)) to hr[i + 1].ts }
            val lowest = rolling.minBy { it.first }
            out["hr_low_at_ts"] = lowest.second.toDouble()
            out["hr_low_hours_before_wake"] = (night.endTs - lowest.second) / 3600.0
            out["hr_low_fraction"] = (lowest.second - night.startTs).toDouble() / max(1L, night.inBed)
        }
        val spo2 = by["spo2"].orEmpty().map { it.value }
        if (spo2.size >= 6) {
            out["spo2_avg"] = mean(spo2)
            out["spo2_min"] = spo2.min()
            out["spo2_below_90_share"] = spo2.count { it < 90 }.toDouble() / spo2.size
        }
        for ((metric, key) in listOf("hrv" to "hrv_avg", "respiration" to "resp_avg")) {
            val values = by[metric].orEmpty().map { it.value }
            if (values.size >= 3) out[key] = mean(values)
        }
        return Vitals(by.mapValues { it.value.size }, out)
    }

    /** How far heart rate fell overnight, against the waking 16 hours before bed. */
    fun hrDip(night: Night, samples: List<Sample>): Dip? {
        val window = night.startTs - 16 * 3600
        val day = samples.filter { it.metric == "heart_rate" && it.ts >= window && it.ts < night.startTs }.map { it.value }
        val asleep = samples.filter { it.metric == "heart_rate" && it.ts in night.startTs..night.endTs }.map { it.value }
        if (day.size < 6 || asleep.size < 6) return null
        return Dip(mean(day), mean(asleep), 1 - mean(asleep) / mean(day))
    }

    private fun score(factors: List<Triple<Pair<String, Int>, Double, String>>): Pair<Int, Int> {
        val possible = factors.sumOf { it.first.second }
        return pyRound(100 * factors.sumOf { it.first.second * it.second } / possible) to possible
    }

    private fun factorList(factors: List<Triple<Pair<String, Int>, Double, String>>) =
        factors.map { (nw, p, d) -> Factor(nw.first, nw.second, pyRound(nw.second * p, 1), d) }

    /** Our own 0-100 score. Missing inputs are left out and the rest rescaled, never guessed. */
    fun sleepScore(night: Night, vitals: Vitals, dip: Dip?, hrvMedian: Double?, target: Long = DEFAULT_SLEEP_TARGET_SECONDS): Score {
        val asleep = night.asleep
        val restorative = if (asleep > 0) (night.seconds.getValue("deep") + night.seconds.getValue("rem")).toDouble() / asleep else 0.0
        val efficiency = night.efficiency ?: 0.0
        val factors = mutableListOf(
            Triple("Duration" to 35, clamp(asleep.toDouble() / target), "${f1(asleep / 3600.0)} h asleep of a ${f0(target / 3600.0)} h target"),
            Triple("Deep and REM" to 20, clamp(restorative / 0.40), "${pct(restorative)} of sleep; full points at 40%"),
            Triple("Efficiency" to 15, clamp((efficiency - 0.70) / 0.20), "${pct(efficiency)} of the night asleep; full points at 90%"),
        )
        if (dip != null) factors += Triple("Heart rate dip" to 10, clamp(dip.dip / 0.10), "${pct(dip.dip)} below daytime; full points at 10%")
        vitals["spo2_avg"]?.let { factors += Triple("Blood oxygen" to 10, clamp((it - 90) / 5), "${f0(it)}% average; full points at 95%") }
        val hrv = vitals["hrv_avg"]
        if (hrv != null && hrvMedian != null && hrvMedian != 0.0) {
            factors += Triple("HRV" to 10, clamp((hrv / hrvMedian - 0.7) / 0.3), "${f0(hrv)} against your usual ${f0(hrvMedian)}; full points at your usual")
        }
        val (value, possible) = score(factors)
        val notes = mutableListOf<String>()
        if (asleep < FULL_NIGHT) notes += "Under 4 hours recorded, so this may be a partial night."
        if (night.seconds.getValue("awake") < 60) notes += "The ring recorded no awake time, so efficiency may read high."
        if (possible < 100) notes += "Based on $possible of 100 possible points; the rest had too little data."
        return Score(value, band(value), if (notes.isEmpty()) "ok" else "low", notes, factorList(factors))
    }

    /** Steps recorded during a night, prorated across partly overlapping periods. Real sleep has few. */
    fun stepsDuring(night: Night, activity: List<Activity>): Int {
        var total = 0.0
        for (a in activity) {
            val overlap = min(a.endTs, night.endTs) - max(a.startTs, night.startTs)
            if (overlap > 0 && a.endTs > a.startTs) total += a.steps * overlap.toDouble() / (a.endTs - a.startTs)
        }
        return pyRound(total)
    }

    fun buildNights(sessions: List<Session>, samples: List<Sample>, activity: List<Activity> = emptyList(),
                    target: Long = DEFAULT_SLEEP_TARGET_SECONDS): List<Night> {
        val nights = groupNights(sessions)
        for (n in nights) {
            n.vitals = overnightVitals(n, samples)
            n.dip = hrDip(n, samples)
            n.stepsDuring = stepsDuring(n, activity)
        }
        val hrvs = nights.mapNotNull { it.vitals["hrv_avg"] }
        val median = if (hrvs.size >= 3) median(hrvs) else null
        for (n in nights) {
            n.score = sleepScore(n, n.vitals, n.dip, median, target)
            if (n.stepsDuring >= MANY_STEPS_DURING_SLEEP) {
                n.score.confidence = "low"
                n.score.notes += "${String.format("%,d", n.stepsDuring)} steps were recorded during this session, so part of it may not be sleep."
            }
        }
        return nights
    }

    data class Consistency(
        val nights: Int, val ready: Boolean, val needed: Int = 3, val bedtimeSpreadMin: Double? = null,
        val wakeSpreadMin: Double? = null, val avgAsleep: Double? = null, val debtSeconds: Long? = null,
    )

    /** Bedtime and wake-time spread, and sleep debt, over the last 7 nights long enough to count. */
    fun consistency(nights: List<Night>, target: Long = DEFAULT_SLEEP_TARGET_SECONDS): Consistency {
        val full = nights.filter { it.asleep >= FULL_NIGHT }.takeLast(7)
        if (full.size < 3) return Consistency(full.size, false)
        fun clock(local: String): Double {
            val minutes = local.substring(11, 13).toInt() * 60 + local.substring(14, 16).toInt()
            return (if (minutes > 720) minutes - 1440 else minutes).toDouble()  // keeps bedtimes either side of midnight together
        }
        return Consistency(full.size, true, 3, pstdev(full.map { clock(it.startLocal) }), pstdev(full.map { clock(it.endLocal) }),
            mean(full.map { it.asleep.toDouble() }), full.sumOf { max(0L, target - it.asleep) })
    }

    data class Recovery(
        val ready: Boolean, val have: Int = 0, val needed: Int = BASELINE_NIGHTS, val night: String? = null,
        val restingHr: Double? = null, val restingHrUsual: Double? = null, val hrv: Double? = null, val hrvUsual: Double? = null,
    )

    /** Compare the latest night's resting heart rate and HRV with your own earlier nights. */
    fun recovery(nights: List<Night>): Recovery {
        val usable = nights.filter { "resting_hr" in it.vitals && it.asleep >= FULL_NIGHT }
        if (usable.size <= BASELINE_NIGHTS) return Recovery(false, max(0, usable.size - 1))
        val latest = usable.last()
        val earlier = usable.subList(usable.size - BASELINE_NIGHTS - 1, usable.size - 1)
        val hrvs = earlier.mapNotNull { it.vitals["hrv_avg"] }
        val hasHrv = hrvs.isNotEmpty() && "hrv_avg" in latest.vitals
        return Recovery(true, night = latest.id, restingHr = latest.vitals["resting_hr"],
            restingHrUsual = median(earlier.map { it.vitals["resting_hr"]!! }),
            hrv = if (hasHrv) latest.vitals["hrv_avg"] else null, hrvUsual = if (hasHrv) median(hrvs) else null)
    }

    /** Your median over up to 14 full earlier nights in the 30 days before this one. Needs 3 to count. */
    private fun usual(nights: List<Night>, night: Night, key: String): Double? {
        val earlier = nights.filter {
            night.startTs - 30 * 86400 <= it.startTs && it.startTs < night.startTs && it.asleep >= FULL_NIGHT && key in it.vitals
        }.takeLast(14)
        return if (earlier.size >= READINESS_MIN_BASELINE) median(earlier.map { it.vitals[key]!! }) else null
    }

    /** Yesterday's steps against your usual. Both far too few and far too many lower it. */
    private fun yesterdayFactor(activity: List<Activity>, date: String): Triple<Pair<String, Int>, Double, String>? {
        val byDay = linkedMapOf<String, Int>()
        for (a in activity) byDay[a.startLocal.substring(0, 10)] = (byDay[a.startLocal.substring(0, 10)] ?: 0) + a.steps
        val day = LocalDate.parse(date)
        val yesterday = day.minusDays(1).toString()
        val window = day.minusDays(15).toString()
        val earlier = byDay.filter { (d, v) -> d >= window && d < yesterday && v > 0 }.values.map { it.toDouble() }
        val steps = byDay[yesterday] ?: 0
        if (steps == 0 || earlier.size < READINESS_MIN_BASELINE) return null
        val usual = median(earlier)
        val ratio = steps / usual
        val points = if (ratio < 1) clamp(ratio / 0.5) else clamp(1 - (ratio - 1.5) / 1.5)
        return Triple("Yesterday's activity" to 10, points,
            "${String.format("%,d", steps)} steps against your usual ${String.format("%,.0f", usual)}; full points from half to 1.5 times usual")
    }

    data class Readiness(
        val ready: Boolean, val reason: String? = null, val night: String? = null, val score: Int? = null, val band: String? = null,
        val possible: Int? = null, val confidence: String? = null, val notes: List<String> = emptyList(), val factors: List<Factor> = emptyList(),
    )

    /**
     * Our own 0-100 daily score, set after waking. It uses the official app's factor names, but our own weights.
     * Factors needing a personal baseline are left out until one exists, and the rest rescaled, never guessed.
     */
    fun readiness(nights: List<Night>, activity: List<Activity>, date: String, target: Long = DEFAULT_SLEEP_TARGET_SECONDS): Readiness {
        val night = nights.firstOrNull { it.id == date }
            ?: return Readiness(false, "No sleep recorded for this date. Readiness is set once a night's sleep has synced.")
        val vitals = night.vitals
        val sleep = night.score
        val factors = mutableListOf(Triple("Last night's sleep" to 35, sleep.score / 100.0, "Sleep score ${sleep.score}"))
        val week = nights.filter { night.startTs - 7 * 86400 < it.startTs && it.startTs <= night.startTs }
        if (week.size >= READINESS_MIN_BASELINE) {
            val average = mean(week.map { it.asleep.toDouble() })
            factors += Triple("Sleep balance" to 10, clamp(average / target),
                "${f1(average / 3600)} h average over ${week.size} recent nights, of a ${f0(target / 3600.0)} h target")
        }
        val usualHr = usual(nights, night, "resting_hr")
        val resting = vitals["resting_hr"]
        if (usualHr != null && usualHr != 0.0 && resting != null) {
            factors += Triple("Resting heart rate" to 20, clamp(1 - (resting - usualHr) / 10),
                "${f0(resting)} BPM against your usual ${f0(usualHr)}; no points at 10 BPM above")
        }
        val usualHrv = usual(nights, night, "hrv_avg")
        val hrv = vitals["hrv_avg"]
        if (usualHrv != null && usualHrv != 0.0 && hrv != null) {
            factors += Triple("HRV balance" to 15, clamp((hrv / usualHrv - 0.7) / 0.3),
                "${f0(hrv)} against your usual ${f0(usualHrv)}; full points at your usual")
        }
        vitals["hr_low_hours_before_wake"]?.let { hours ->
            factors += Triple("Recovery index" to 10, clamp(hours / 6), "Heart rate reached its low ${f1(hours)} h before waking; full points at 6 h")
        }
        yesterdayFactor(activity, date)?.let { factors += it }
        val (value, possible) = score(factors)
        val notes = mutableListOf<String>()
        if (possible < 100) notes += "Based on $possible of 100 possible points. The rest need more nights and days of data, so this will change as your baseline builds."
        // Name the sleep score's own reasons, except its points note, which this card already covers.
        notes += sleep.notes.filterNot { it.startsWith("Based on") }.map { "Sleep score: $it" }
        return Readiness(true, null, night.id, value, band(value), possible, if (notes.isEmpty()) "ok" else "low", notes, factorList(factors))
    }

    data class WorkoutTotals(val steps: Int?, val distanceM: Int?, val kcal: Int?, val hrAvg: Int?, val hrMin: Int?, val hrMax: Int?)

    /** Totals from the ring's live counters. The counters may be for the whole day, so only the change counts. */
    fun workoutTotals(first: IntArray?, last: IntArray?, heart: List<Int>): WorkoutTotals {
        val change = if (first != null && last != null) IntArray(3) { max(0, last[it] - first[it]) } else null
        return WorkoutTotals(change?.get(0), change?.get(1), change?.get(2),
            if (heart.isNotEmpty()) pyRound(heart.average()) else null, heart.minOrNull(), heart.maxOrNull())
    }

    data class DayMetric(val id: String, val name: String, val value: Double, val unit: String, val formula: String,
                         val inputs: String, val caveat: String? = null)

    /** Derived numbers for one day. An item only appears when its inputs exist. */
    fun dayMetrics(samples: List<Sample>, activity: List<Activity>, night: Night? = null): List<DayMetric> {
        val items = mutableListOf<DayMetric>()
        val byMetric = samples.groupBy { it.metric }
        val sysRows = byMetric["bp_systolic"].orEmpty()
        val diaRows = byMetric["bp_diastolic"].orEmpty().associate { it.ts to it.value }
        val row = sysRows.lastOrNull { it.ts in diaRows }
        if (row != null) {
            val dia = diaRows.getValue(row.ts)
            val estimate = "Uses the ring's blood pressure estimate."
            val reading = "${f0(row.value)}/${f0(dia)} at ${row.local.substring(11, 16)}"
            items += DayMetric("pulse_pressure", "Pulse pressure", row.value - dia, "mmHg", "Systolic − diastolic", reading, estimate)
            items += DayMetric("map", "Mean arterial pressure", pyRound(dia + (row.value - dia) / 3).toDouble(), "mmHg",
                "Diastolic + ⅓ × pulse pressure", reading, estimate)
            val nearest = byMetric["heart_rate"].orEmpty().filter { kotlin.math.abs(it.ts - row.ts) <= 180 }.minByOrNull { kotlin.math.abs(it.ts - row.ts) }
            if (nearest != null) {
                items += DayMetric("rate_pressure", "Heart workload", pyRound(row.value * nearest.value).toDouble(), "RPP",
                    "Heart rate × systolic", "${f0(nearest.value)} BPM × ${f0(row.value)} mmHg", estimate)
            }
        }
        val steps = activity.sumOf { it.steps }
        val distance = activity.sumOf { it.distanceM }
        if (steps >= 100 && distance != 0) {
            items += DayMetric("stride", "Average stride", pyRound(distance.toDouble() / steps, 2), "m", "Distance ÷ steps",
                "${String.format("%,d", distance)} m over ${String.format("%,d", steps)} steps", "The ring's distance is itself estimated from steps.")
        }
        val peak = activity.maxByOrNull { it.steps }
        if (peak != null && peak.steps != 0) {
            val minutes = max(1.0, (peak.endTs - peak.startTs) / 60.0)
            items += DayMetric("peak_cadence", "Busiest half hour", pyRound(peak.steps / minutes).toDouble(), "steps/min",
                "Steps ÷ minutes in the busiest period", "${String.format("%,d", peak.steps)} steps from ${peak.startLocal.substring(11, 16)}")
        }
        night?.dip?.let { dip ->
            items += DayMetric("hr_dip", "Overnight heart rate dip", pyRound(dip.dip * 100).toDouble(), "%", "1 − night average ÷ day average",
                "${f0(dip.nightAvg)} BPM asleep, ${f0(dip.dayAvg)} BPM in the 16 h before")
        }
        return items
    }

    /** One row per calendar day for the trend charts. A day only has the values it has data for. */
    fun dailyRollup(samples: List<Sample>, activity: List<Activity>, nights: List<Night>): List<Map<String, Any?>> {
        val days = linkedMapOf<String, LinkedHashMap<String, MutableList<Double>>>()
        for (s in samples) days.getOrPut(s.local.substring(0, 10)) { linkedMapOf() }.getOrPut(s.metric) { mutableListOf() } += s.value
        val rows = linkedMapOf<String, LinkedHashMap<String, Any?>>()
        for ((d, metrics) in days) rows[d] = linkedMapOf<String, Any?>("date" to d).apply { metrics.forEach { (m, v) -> put(m, mean(v)) } }
        for (a in activity) {
            val d = a.startLocal.substring(0, 10)
            val row = rows.getOrPut(d) { linkedMapOf("date" to d) }
            row["steps"] = ((row["steps"] as Int?) ?: 0) + a.steps
            row["distance_m"] = ((row["distance_m"] as Int?) ?: 0) + a.distanceM
            row["kcal"] = ((row["kcal"] as Int?) ?: 0) + a.kcal
        }
        for (n in nights) {
            val row = rows.getOrPut(n.id) { linkedMapOf("date" to n.id) }
            row["asleep"] = n.asleep; row["deep"] = n.seconds["deep"]; row["light"] = n.seconds["light"]; row["rem"] = n.seconds["rem"]
            row["awake"] = n.seconds["awake"]; row["sleep_score"] = n.score.score; row["resting_hr"] = n.vitals["resting_hr"]
            row["readiness"] = readiness(nights, activity, n.id).score
        }
        return rows.keys.sorted().map { rows.getValue(it) }
    }
}
