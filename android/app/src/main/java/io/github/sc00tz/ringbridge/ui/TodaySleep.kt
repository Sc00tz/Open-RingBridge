package io.github.sc00tz.ringbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sc00tz.ringbridge.data.ClockFix
import io.github.sc00tz.ringbridge.data.Derived
import io.github.sc00tz.ringbridge.data.Store
import io.github.sc00tz.ringbridge.ring.RingOps
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ----- formatting. Ring times are wall-clock text.
fun parseLocal(s: String): LocalDateTime = LocalDateTime.parse(s.replace(' ', 'T'))
fun timeOf(s: String): String = parseLocal(s).format(DateTimeFormatter.ofPattern("h:mm a"))
fun longDate(s: String): String = LocalDate.parse(s.substring(0, 10)).format(DateTimeFormatter.ofPattern("EEE, MMM d"))
fun hm(seconds: Long): String { val m = Math.round(seconds / 60.0); return "${m / 60}h ${"%02d".format(m % 60)}m" }
fun fmt(v: Double, digits: Int = 0): String = if (digits == 0) "%,.0f".format(v) else "%,.${digits}f".format(v)
private fun span(a: String, b: String) = "${longDate(a)} ${timeOf(a)} – ${longDate(b)} ${timeOf(b)}"
fun hoursText(sec: Long) = if (sec % 3600 == 0L) "${sec / 3600} hours" else "%.1f hours".format(sec / 3600.0)

@Composable
fun bands(): List<Band> {
    val p = LocalPalette.current
    return listOf(Band("Poor", 0, 60, p.critical), Band("Average", 60, 70, p.warning), Band("Good", 70, 85, p.good), Band("Optimal", 85, 100, p.best))
}

@Composable
fun ClockProposal(vm: AppViewModel, f: ClockFix.StoredFix) {
    Panel("The ring's clock was behind",
        "By about ${hoursText(f.offset)}. This usually means its battery ran flat: the clock stops while it's dead, then carries on from where it stopped.") {
        Kv("Dated now", span(Store.toLocal(f.fromTs), Store.toLocal(f.toTs)), divider = false)
        Kv("Likely real time", span(Store.toLocal(f.fromTs + f.offset), Store.toLocal(f.toTs + f.offset)))
        Note("If you instead took the ring off and put it back on just before syncing, choose Leave them. Moving can be undone from the Ring screen.")
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.decideClock(f.id, "apply") }) { Text("Move them") }
            TextButton(onClick = { vm.decideClock(f.id, "dismiss") }) { Text("Leave them") }
        }
    }
}

@Composable
fun Factors(factors: List<Derived.Factor>) {
    for (f in factors) {
        Kv(f.name, "${fmt(f.earned, 1)} of ${f.weight}")
        Text(f.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TodayScreen(vm: AppViewModel, goTab: (String) -> Unit) {
    val d by vm.day.collectAsState()
    val proposals by vm.proposals.collectAsState()
    val p = LocalPalette.current
    Screen {
        items(proposals.size) { ClockProposal(vm, proposals[it]) }
        val day = d
        if (day == null) {
            item { Panel("No data yet", "Find your ring on the Ring screen, then press Sync.") }
            return@Screen
        }
        item { Stepper(longDate(day.date), day.prev, day.next, vm::goDay) }
        if (day.isLatest && day.date != LocalDate.now().toString()) item {
            val newest = day.series.values.flatten().maxByOrNull { it.ts }
            Notice("No newer readings.", "This is the latest day with data" + (newest?.let { "; the last reading was at ${timeOf(it.local)}" } ?: "") +
                ". If you've worn the ring since then, check it's charged and sync again.")
        }
        item {
            val r = day.readiness
            Panel("Readiness") {
                Gauge(r.score, bands(), if (r.ready) "Readiness ${r.score}, ${r.band}" else "Readiness not set yet")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    if (r.ready) StatusLabel(r.band!!) else Text("Not set yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!r.ready) Note(r.reason ?: "")
                if (r.notes.isNotEmpty()) Notice("Limited data.", r.notes.joinToString(" "))
                if (r.ready) Expandable("How this was worked out") {
                    Note("Our own weights, using the factors the official app names. Updated once a day, after sleep. The ring has no usable temperature, so that factor is left out.")
                    Factors(r.factors)
                }
            }
        }
        day.latest("heart_rate")?.let { hr ->
            item {
                val points = day.series["heart_rate"].orEmpty()
                Panel {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text("Heart rate, latest", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(fmt(hr.value), fontSize = 44.sp, fontWeight = FontWeight.SemiBold)
                                Text(" BPM", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                            }
                            Text("at ${timeOf(hr.local)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (points.size > 1) Column(horizontalAlignment = Alignment.End) {
                            Note("Range ${fmt(points.minOf { it.value })}–${fmt(points.maxOf { it.value })}")
                            Text("Average ${fmt(points.map { it.value }.average())}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    dayChart(points, "Heart rate", p.s1, "BPM", day.date)
                }
            }
        }
        item {
            val sbp = day.latest("bp_systolic"); val dbp = day.latest("bp_diastolic")
            val steps = day.activity.sumOf { it.steps }
            val tiles = mutableListOf<@Composable (Modifier) -> Unit>(
                { m -> Tile("Blood pressure", if (sbp != null && dbp != null) "${fmt(sbp.value)}/${fmt(dbp.value)}" else null, "mmHg", sbp?.let { "at ${timeOf(it.local)}" }, true, m) },
                { m -> Tile("Steps", if (day.activity.isNotEmpty()) fmt(steps.toDouble()) else null, "",
                    "${fmt(day.activity.sumOf { it.distanceM } / 1000.0, 1)} km, ${day.activity.sumOf { it.kcal }} kcal", modifier = m) },
            )
            for ((key, label, unit, digits) in listOf(Quad("spo2", "Blood oxygen", "%", 0), Quad("hrv", "HRV", "ms", 0),
                Quad("respiration", "Breathing", "/min", 0), Quad("stress", "Stress", "", 1), Quad("glucose", "Glucose", "mmol/L", 1))) {
                val v = day.latest(key)
                tiles += { m -> Tile(label, v?.let { fmt(it.value, digits) }, unit, v?.let { "at ${timeOf(it.local)}" }, key == "glucose", m) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { pair.forEach { it(Modifier.weight(1f)) }; if (pair.size == 1) Column(Modifier.weight(1f)) {} }
                }
            }
        }
        val night = day.night
        if (night != null) item {
            Panel("Sleep", onClick = { vm.goNight(night.id); goTab("sleep") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(hm(night.asleep), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        Text("${timeOf(night.startLocal)} – ${timeOf(night.endLocal)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${night.score.score}", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        StatusLabel(night.score.band)
                    }
                }
            }
        } else if (day.latestNight != null) item {
            Panel("Sleep", "None recorded for this date. Latest night: ${longDate(day.latestNight)}.", onClick = { vm.goNight(day.latestNight); goTab("sleep") })
        }
        items(day.workouts.size) { WorkoutCard(day.workouts[it], vm) }
        if (day.activity.isNotEmpty()) item {
            Panel("Steps by half hour") {
                Columns(day.activity.map { it.steps.toDouble() }, day.activity.map { timeOf(it.startLocal).replace(":00", "") }, p.s1, "steps")
            }
        }
        for ((key, label, unit) in listOf(Triple("spo2", "Blood oxygen", "%"), Triple("hrv", "HRV", "ms"))) {
            val points = day.series[key].orEmpty()
            if (points.size > 1) item { Panel(label) { dayChart(points, label, p.s1, unit, day.date) } }
        }
        if (day.metrics.isNotEmpty()) item {
            Panel("Worked out from your readings", "Our own formulas, shown so you can check them. Wellness estimates, not medical readings.") {
                for (m in day.metrics) {
                    Kv(m.name, "${fmt(m.value, if (m.unit == "m") 2 else 0)} ${m.unit}")
                    Text("${m.formula}: ${m.inputs}" + (m.caveat?.let { ". $it" } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

data class Quad(val a: String, val b: String, val c: String, val d: Int)

@Composable
fun dayChart(points: List<Derived.Sample>, name: String, color: androidx.compose.ui.graphics.Color, unit: String, date: String) {
    if (points.size < 2) return
    val start = Store.toTs("$date 00:00:00")
    LineChart(listOf(Series(name, color, points.map { it.ts to it.value })), start, start + 86400, unit)
}

@Composable
fun WorkoutCard(w: Store.Workout, vm: AppViewModel) {
    val label = w.kind?.let { RingOps.WORKOUTS[it]?.first } ?: RingOps.WORKOUTS.values.firstOrNull { it.second == w.ringCode }?.first ?: "Workout (ring type ${w.ringCode})"
    Panel(label, "${longDate(w.startLocal)}, ${timeOf(w.startLocal)} · ${hm((w.endTs ?: w.startTs) - w.startTs)}") {
        if (w.status == "lost") Notice(null, "The connection dropped before this ended, so it may be incomplete.")
        w.steps?.let { Kv("Steps", fmt(it.toDouble()), divider = false) }
        w.distanceM?.let { Kv("Distance", "${fmt(it / 1000.0, 2)} km") }
        w.kcal?.let { Kv("Calories", "$it kcal") }
        w.hrAvg?.let { Kv("Heart rate", "$it average, ${w.hrMin}–${w.hrMax}") }
        val heart = vm.store.samples(startTs = w.startTs, endTs = w.endTs ?: w.startTs).filter { it.metric == "heart_rate" }
        if (heart.size > 1) LineChart(listOf(Series("Heart rate", LocalPalette.current.s1, heart.map { it.ts to it.value })), w.startTs, w.endTs ?: w.startTs, "BPM")
    }
}

@Composable
fun SleepScreen(vm: AppViewModel) {
    val s by vm.sleep.collectAsState()
    val p = LocalPalette.current
    val colors = mapOf("awake" to p.s4, "rem" to p.s2, "light" to p.s3, "deep" to p.s1)
    Screen {
        val sleep = s
        val n = sleep?.night
        if (sleep == null || n == null) { item { Panel("No sleep yet", "Wear the ring overnight, then sync in the morning.") }; return@Screen }
        item { Stepper("Woke ${longDate(n.id)}", sleep.prev, sleep.next, vm::goNight) }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Asleep", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(hm(n.asleep), fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                        Text("${timeOf(n.startLocal)} – ${timeOf(n.endLocal)}" + if (n.sessions > 1) " · ${n.sessions} sessions" else "",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Sleep score", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${n.score.score}", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                        StatusLabel(n.score.band)
                    }
                }
                if (n.score.notes.isNotEmpty()) Notice("Limited data.", n.score.notes.joinToString(" "))
                Expandable("How this was worked out") { Factors(n.score.factors) }
            }
        }
        item {
            Panel("Stages") {
                Hypnogram(n.segments, n.startTs, n.endTs, colors)
                for ((key, name) in STAGE_ROWS.reversed()) Kv(name, hm(n.seconds[key] ?: 0), divider = key != "deep")
            }
        }
        if (sleep.heart.size > 1) item {
            Panel("Heart rate overnight") {
                LineChart(listOf(Series("Heart rate", p.s1, sleep.heart.map { it.ts to it.value })), n.startTs, n.endTs, "BPM")
                n.vitals["resting_hr"]?.let { Kv("Resting (lowest 10%)", "${fmt(it)} BPM") }
                n.dip?.let { Kv("Dip below daytime", "${fmt(it.dip * 100)}%") }
                n.vitals["spo2_avg"]?.let { Kv("Blood oxygen average", "${fmt(it)}%") }
                n.vitals["hrv_avg"]?.let { Kv("HRV average", "${fmt(it)} ms") }
            }
        }
        item {
            val c = sleep.consistency
            Panel("Consistency", "Over your last ${c.nights} full nights") {
                if (!c.ready) Note("Needs ${c.needed} full nights; ${c.nights} so far.") else {
                    Kv("Bedtime varies by", "±${fmt(c.bedtimeSpreadMin!!)} min", divider = false)
                    Kv("Wake time varies by", "±${fmt(c.wakeSpreadMin!!)} min")
                    Kv("Average asleep", hm(c.avgAsleep!!.toLong()))
                    Kv("Short of 8 h, in total", hm(c.debtSeconds!!))
                }
            }
        }
        item {
            val r = sleep.recovery
            Panel("Recovery", "Last night against your usual") {
                if (!r.ready) Note("Needs ${r.needed} earlier full nights; ${r.have} so far.") else {
                    Kv("Resting heart rate", "${fmt(r.restingHr!!)} (usual ${fmt(r.restingHrUsual!!)})", divider = false)
                    if (r.hrv != null) Kv("HRV", "${fmt(r.hrv)} (usual ${fmt(r.hrvUsual!!)})")
                }
            }
        }
    }
}
