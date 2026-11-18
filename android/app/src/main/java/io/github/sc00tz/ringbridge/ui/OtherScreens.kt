package io.github.sc00tz.ringbridge.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sc00tz.ringbridge.BuildConfig
import io.github.sc00tz.ringbridge.data.Store
import io.github.sc00tz.ringbridge.ring.RingOps
import kotlinx.coroutines.delay

// ----- Trends
@Composable
fun TrendsScreen(vm: AppViewModel) {
    val rows by vm.trends.collectAsState()
    var range by rememberSaveable { mutableStateOf(vm.trendDays) }
    val p = LocalPalette.current
    fun series(key: String) = rows.mapNotNull { r -> (r[key] as? Number)?.let { Store.toTs("${r["date"]} 12:00:00") to it.toDouble() } }
    Screen {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((days, name) in listOf(7 to "7 days", 30 to "30 days", 90 to "90 days", 0 to "All")) {
                    FilterChip(selected = range == days, onClick = { range = days; vm.chooseTrendDays(days) }, label = { Text(name) })
                }
            }
        }
        if (rows.isEmpty()) { item { Panel("Nothing to show yet", "Trends fill in as you sync.") }; return@Screen }
        val first = Store.toTs("${rows.first()["date"]} 00:00:00")
        val last = Store.toTs("${rows.last()["date"]} 23:59:59")
        for ((key, label, unit) in listOf(Triple("readiness", "Readiness", ""), Triple("sleep_score", "Sleep score", ""),
            Triple("resting_hr", "Resting heart rate", "BPM"), Triple("hrv", "HRV", "ms"), Triple("spo2", "Blood oxygen", "%"))) {
            val points = series(key)
            if (points.size > 1) item { Panel(label) { LineChart(listOf(Series(label, p.s1, points)), first, last, unit, days = true) } }
        }
        val steps = rows.filter { it["steps"] != null }
        if (steps.isNotEmpty()) item {
            Panel("Steps") { Columns(steps.map { (it["steps"] as Number).toDouble() }, steps.map { (it["date"] as String).substring(5).replace("-", "/") }, p.s1, "steps") }
        }
        val asleep = rows.filter { it["asleep"] != null }
        if (asleep.isNotEmpty()) item {
            Panel("Time asleep", "Hours per night") {
                Columns(asleep.map { (it["asleep"] as Number).toDouble() / 3600 }, asleep.map { (it["date"] as String).substring(5).replace("-", "/") }, p.s3, "h")
            }
        }
        item { Note("Only days with data are shown, so a gap in time doesn't appear as a gap in the columns.") }
    }
}

// ----- Record: workouts and spot measurements
@Composable
fun RecordScreen(vm: AppViewModel) {
    val ring by vm.ring.collectAsState()
    val job by vm.job.collectAsState()
    val workout by vm.workout.collectAsState()
    val past by vm.workouts.collectAsState()
    val p = LocalPalette.current
    val context = LocalContext.current
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    Screen {
        if (ring?.address == null) { item { Panel("No ring yet", "Find your ring on the Ring screen first.") }; return@Screen }
        item {
            if (workout.running) {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
                val u = workout.update
                Panel("${RingOps.WORKOUTS[workout.kind]?.first} · ${workout.message}") {
                    val sec = (now - workout.startedAt) / 1000
                    Text("%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60), fontSize = 44.sp, fontWeight = FontWeight.SemiBold)
                    Kv("Heart rate", u?.heart?.let { "$it BPM" } ?: "Waiting", divider = false)
                    Kv("Steps", u?.totals?.steps?.toString() ?: "–")
                    Kv("Distance", u?.totals?.distanceM?.let { "${fmt(it / 1000.0, 2)} km" } ?: "–")
                    Kv("Calories", u?.totals?.kcal?.let { "$it kcal" } ?: "–")
                    if (u != null && u.points.size > 1) LineChart(listOf(Series("Heart rate", p.s1, u.points.map { it.first to it.second.toDouble() })),
                        u.points.first().first, u.points.last().first, "BPM")
                    if (workout.kind == "bike") Note("Bike distance is the ring's estimate from steps, so it won't mean much. GPS distance is planned.")
                    Button(onClick = vm::stopWorkout, modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) { Text("Stop") }
                }
            } else {
                Panel("Start a workout", "Keeps recording with the screen off. Stay within Bluetooth range of your phone.") {
                    workout.error?.let { Notice("Didn't start.", it) }
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((kind, pair) in RingOps.WORKOUTS) {
                            FilledTonalButton(onClick = {
                                if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                    notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                WorkoutServiceClear()
                                vm.startWorkout(kind)
                            }, enabled = job?.running != true, modifier = Modifier.weight(1f).height(56.dp)) { Text(pair.first) }
                        }
                    }
                }
            }
        }
        item { Text("Spot measurement", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
        for ((kind, m) in RingOps.MEASURES) item {
            val mine = job?.kind == kind
            Panel(m.label, "About ${m.seconds} seconds. Keep still with the ring snug.") {
                if (mine && job?.values?.isNotEmpty() == true) Text(job!!.values.values.joinToString("/") { fmt(it, if (kind == "glucose") 1 else 0) },
                    fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                if (mine && job?.error != null) Notice(null, job!!.error!!)
                if (mine && job?.running == false && job?.error == null) Note(job!!.message)
                Row(Modifier.padding(top = 8.dp)) {
                    if (mine && job?.running == true) FilledTonalButton(onClick = vm::cancelMeasure) { Text("Stop") }
                    else FilledTonalButton(onClick = { vm.measure(kind) }, enabled = job?.running != true && !workout.running) { Text("Start") }
                }
            }
        }
        if (past.isNotEmpty()) {
            item { Text("Past workouts", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
            items(past.size) { WorkoutCard(past[it], vm) }
        }
    }
}

@Suppress("FunctionName")
private fun WorkoutServiceClear() = io.github.sc00tz.ringbridge.work.WorkoutService.clear()

// ----- Ring: the ring itself, the hub, and corrections
@Composable
fun RingScreen(vm: AppViewModel) {
    val ring by vm.ring.collectAsState()
    val found by vm.found.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val hubMessage by vm.hubMessage.collectAsState()
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) vm.scan()
    }
    val r = ring ?: return
    Screen {
        items(r.fixes.count { it.status == "proposed" }) { ClockProposal(vm, r.fixes.filter { it.status == "proposed" }[it]) }
        item {
            if (r.address == null) {
                Panel("Find your ring", "Close the official app first, since the ring allows one connection at a time. Keep the ring nearby and charged.") {
                    Button(onClick = {
                        val need = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                        if (need.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) vm.scan() else permissions.launch(need.toTypedArray())
                    }, enabled = !scanning, modifier = Modifier.padding(top = 12.dp)) { Text(if (scanning) "Looking…" else "Look for rings") }
                    if (found.isEmpty() && scanning) Note("Rings in this family show up with names like “R01L C923”.")
                    for ((address, name) in found) {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.Medium); Text(address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            TextButton(onClick = { vm.chooseRing(address, name) }) { Text("Use this") }
                        }
                    }
                }
            } else {
                val device = r.device?.second
                Panel(r.name ?: "Ring", r.device?.let { "Last read ${AppViewModel.ago(it.first)}" } ?: "Press Sync to read it") {
                    if (device != null) {
                        Kv("Battery", "${device.optInt("battery")}%" + if (device.optBoolean("charging")) ", charging" else "", divider = false)
                        Kv("Firmware", device.optString("firmware"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Sync in the background")
                            Text("About every 6 hours. The ring only keeps about a day of heart readings.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = r.backgroundSync, onCheckedChange = vm::setBackgroundSync)
                    }
                    TextButton(onClick = vm::forgetRing, modifier = Modifier.padding(top = 4.dp)) { Text("Use a different ring") }
                }
            }
        }
        val applied = r.fixes.filter { it.status == "applied" }
        if (applied.isNotEmpty()) item {
            Panel("Clock corrections", "Readings moved because the ring's clock was behind when it stamped them.") {
                for (f in applied) {
                    Kv("${hoursText(f.offset)} later", "${longDate(Store.toLocal(f.fromTs + f.offset))} – ${longDate(Store.toLocal(f.toTs + f.offset))}")
                    TextButton(onClick = { vm.decideClock(f.id, "undo") }) { Text("Undo") }
                }
            }
        }
        item { HubPanel(vm, r, hubMessage) }
        item {
            Panel("What syncing does") {
                Kv("Sets the ring's clock", "Yes", divider = false)
                Kv("Reads and backs up raw history", "Yes")
                Kv("Acknowledges history", "No")
                Kv("Deletes anything from the ring", "No")
            }
        }
        item { Note("Open RingBridge ${BuildConfig.VERSION_NAME}. Wellness estimates, not medical readings.") }
    }
}

@Composable
private fun HubPanel(vm: AppViewModel, r: AppViewModel.Ring, message: String?) {
    if (r.hubSignedIn) {
        Panel("Hub", "Signed in to ${r.hubUrl} as ${r.hubUser}") {
            Kv("Waiting to upload", "${r.pending}", divider = false)
            Kv("Last upload", if (r.lastUpload > 0) AppViewModel.ago(r.lastUpload) else "Not yet")
            r.hubError?.let { Notice("Last upload failed.", it) }
            message?.let { Note(it) }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.hubUploadNow() }) { Text("Upload now") }
                TextButton(onClick = vm::hubSignOut) { Text("Sign out") }
            }
        }
        return
    }
    var url by rememberSaveable { mutableStateOf(r.hubUrl ?: "") }
    var user by rememberSaveable { mutableStateOf(r.hubUser ?: "") }
    var password by remember { mutableStateOf("") }
    Panel("Hub (optional)", "Your own server, for seeing your data in a browser and keeping a copy off the phone. Everything works without one.") {
        OutlinedTextField(url, { url = it }, label = { Text("Hub address") }, placeholder = { Text("https://ring.example.com") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        message?.let { Note(it) }
        Button(onClick = { vm.hubSignIn(url, user, password); password = "" }, enabled = url.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.padding(top = 12.dp)) { Text("Sign in") }
        if (r.pending > 0) Note("${r.pending} syncs are waiting, and will upload once you sign in.")
    }
}
