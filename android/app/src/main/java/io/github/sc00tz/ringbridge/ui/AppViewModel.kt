package io.github.sc00tz.ringbridge.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sc00tz.ringbridge.RingBridgeApp
import io.github.sc00tz.ringbridge.data.ClockFix
import io.github.sc00tz.ringbridge.data.Derived
import io.github.sc00tz.ringbridge.data.Store
import io.github.sc00tz.ringbridge.ring.RingOps
import io.github.sc00tz.ringbridge.work.SyncWorker
import io.github.sc00tz.ringbridge.work.WorkoutService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate

/** Screen state for all five screens. Each load mirrors a function in core/views.py. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = RingBridgeApp.of(application)
    val store: Store = app.store
    val settings = app.settings
    val hub = app.hub

    data class Day(
        val date: String, val prev: String?, val next: String?, val series: Map<String, List<Derived.Sample>>,
        val activity: List<Derived.Activity>, val metrics: List<Derived.DayMetric>, val night: Derived.Night?,
        val readiness: Derived.Readiness, val workouts: List<Store.Workout>, val latestNight: String?, val isLatest: Boolean,
    ) {
        fun latest(metric: String) = series[metric]?.lastOrNull()
    }

    data class Sleep(
        val night: Derived.Night?, val prev: String?, val next: String?, val heart: List<Derived.Sample>,
        val consistency: Derived.Consistency, val recovery: Derived.Recovery,
    )

    data class Ring(
        val address: String?, val name: String?, val device: Pair<Long, JSONObject>?, val lastSync: Pair<Long, JSONObject>?,
        val fixes: List<ClockFix.StoredFix>, val hubUrl: String?, val hubUser: String?, val hubSignedIn: Boolean,
        val pending: Int, val lastUpload: Long, val hubError: String?, val backgroundSync: Boolean,
    )

    /** A ring operation in progress or just finished. */
    data class Job(val label: String, val message: String, val running: Boolean, val error: String? = null,
                   val values: Map<String, Double> = emptyMap(), val kind: String? = null)

    val day = MutableStateFlow<Day?>(null)
    val sleep = MutableStateFlow<Sleep?>(null)
    val trends = MutableStateFlow<List<Map<String, Any?>>>(emptyList())
    val ring = MutableStateFlow<Ring?>(null)
    val job = MutableStateFlow<Job?>(null)
    val proposals = MutableStateFlow<List<ClockFix.StoredFix>>(emptyList())
    val workouts = MutableStateFlow<List<Store.Workout>>(emptyList())
    val found = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val scanning = MutableStateFlow(false)
    val workout: StateFlow<WorkoutService.State> = WorkoutService.state

    var date: String? = null
    var nightId: String? = null
    var trendDays = 30
    private var nights: List<Derived.Night> = emptyList()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            loadRing()  // quick, and the header needs it
            nights = Derived.buildNights(store.sleep(), store.samples(), store.activity())
            loadDay(); loadSleep(); loadTrends()
            proposals.value = store.clockFixes(status = "proposed")
            workouts.value = store.workouts()
        }
    }

    private fun loadDay() {
        val days = store.days()
        val today = LocalDate.now().toString()
        val d = date ?: (if (today in days) today else days.lastOrNull()) ?: run { day.value = null; return }
        date = d
        val samples = store.samples(date = d)
        val activity = store.activity(date = d)
        val night = nights.firstOrNull { it.id == d }
        day.value = Day(d, days.lastOrNull { it < d }, days.firstOrNull { it > d }, samples.groupBy { it.metric }, activity,
            Derived.dayMetrics(samples, activity, night), night, Derived.readiness(nights, store.activity(), d), store.workouts(date = d),
            nights.lastOrNull()?.id, days.none { it > d })
    }

    private fun loadSleep() {
        val ids = nights.map { it.id }
        val id = nightId?.takeIf { it in ids } ?: ids.lastOrNull()
        nightId = id
        val night = nights.firstOrNull { it.id == id }
        val i = ids.indexOf(id)
        sleep.value = Sleep(night, if (i > 0) ids[i - 1] else null, if (i in 0 until ids.size - 1) ids[i + 1] else null,
            night?.let { n -> store.samples(startTs = n.startTs, endTs = n.endTs).filter { it.metric == "heart_rate" } }.orEmpty(),
            Derived.consistency(nights), Derived.recovery(nights))
    }

    private fun loadTrends() {
        val rows = Derived.dailyRollup(store.samples(), store.activity(), nights)
        val cutoff = if (trendDays > 0) LocalDate.now().minusDays(trendDays - 1L).toString() else ""
        trends.value = rows.filter { (it["date"] as String) >= cutoff }
    }

    private fun loadRing() {
        ring.value = Ring(settings.ringAddress, settings.ringName, store.device(), store.lastSync(), store.clockFixes(), hub.url, hub.username,
            hub.signedIn, store.pendingCount(), hub.lastUpload, hub.lastError, settings.backgroundSync)
    }

    fun goDay(d: String) { date = d; refresh() }
    fun goNight(id: String) { nightId = id; refresh() }
    fun chooseTrendDays(days: Int) { trendDays = days; refresh() }

    private fun run(label: String, kind: String? = null, block: suspend (update: (String) -> Unit) -> String) {
        if (job.value?.running == true) return
        job.value = Job(label, "Connecting", true, kind = kind)
        viewModelScope.launch {
            try {
                val done = withContext(Dispatchers.IO) { block { msg -> job.value = job.value?.copy(message = msg) } }
                job.value = job.value?.copy(running = false, message = done)
            } catch (e: Exception) {
                job.value = job.value?.copy(running = false, error = e.message ?: "Something went wrong.")
            }
            date = null
            refresh()
        }
    }

    fun sync() {
        val address = settings.ringAddress ?: return
        run("Sync") { update ->
            val r = RingOps.sync(getApplication(), store, address, update)
            var note = if (r.failed.isEmpty()) "Synced just now." else "Synced, but ${r.failed.joinToString()} failed the checksum and wasn't imported."
            if (r.clockProposals > 0) note = "Synced. The ring's clock looks like it was behind; see Today."
            else if (r.newestBeforeTs != null && Store.now() - r.newestBeforeTs > 30 * 60) {
                note = "Synced. The newest reading was ${ago(r.newestBeforeTs)}. If you've been wearing the ring, sync again in about 5 minutes so its clock can be checked."
            }
            if (hub.signedIn) {
                update("Uploading to the hub")
                runCatching { hub.flush(store) }.onFailure { note += " Upload to the hub failed: ${it.message}" }
            }
            note
        }
    }

    @Volatile private var cancelMeasure = false
    fun measure(kind: String) {
        val address = settings.ringAddress ?: return
        cancelMeasure = false
        run(RingOps.MEASURES.getValue(kind).label, kind) { update ->
            val values = RingOps.measure(getApplication(), store, address, kind, { v ->
                job.value = job.value?.copy(message = "Measuring", values = v)
            }, { cancelMeasure })
            if (hub.signedIn) runCatching { hub.flush(store) }
            if (values.isEmpty()) "Cancelled." else "Saved."
        }
    }
    fun cancelMeasure() { cancelMeasure = true }

    fun startWorkout(kind: String) { WorkoutService.start(getApplication(), kind) }
    fun stopWorkout() = WorkoutService.stop()

    fun decideClock(id: Long, action: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { runCatching { store.decideClockFix(id, action) } }
        date = null
        refresh()
    }

    fun setBackgroundSync(on: Boolean) {
        settings.backgroundSync = on
        if (on && settings.ringAddress != null) SyncWorker.schedule(getApplication()) else SyncWorker.cancel(getApplication())
        refresh()
    }

    // ----- finding the ring -----
    @SuppressLint("MissingPermission")
    fun scan() {
        val scanner = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        val seen = linkedMapOf<String, String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                seen[result.device.address] = name
                // Likely rings first: this family advertises names like "R01L C923".
                found.value = seen.entries.sortedByDescending { it.value.startsWith("R0") }.map { it.key to it.value }
            }
        }
        found.value = emptyList()
        scanning.value = true
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        viewModelScope.launch {
            delay(15_000)
            runCatching { scanner.stopScan(callback) }
            scanning.value = false
        }
    }

    fun chooseRing(address: String, name: String) {
        settings.ringAddress = address
        settings.ringName = name
        if (settings.backgroundSync) SyncWorker.schedule(getApplication())
        found.value = emptyList()
        refresh()
    }

    fun forgetRing() {
        settings.ringAddress = null
        settings.ringName = null
        SyncWorker.cancel(getApplication())
        refresh()
    }

    // ----- hub -----
    val hubMessage = MutableStateFlow<String?>(null)
    fun hubSignIn(url: String, user: String, password: String) = viewModelScope.launch {
        hubMessage.value = "Signing in…"
        hubMessage.value = withContext(Dispatchers.IO) {
            runCatching { hub.signIn(url, user, password); hub.flush(store); "Signed in. Uploads go to this hub from now on." }
                .getOrElse { it.message ?: "Couldn't sign in." }
        }
        refresh()
    }

    fun hubSignOut() { hub.signOut(); hubMessage.value = null; refresh() }
    fun hubUploadNow() = viewModelScope.launch {
        hubMessage.value = withContext(Dispatchers.IO) { runCatching { "Uploaded ${hub.flush(store)}." }.getOrElse { it.message } }
        refresh()
    }

    companion object {
        fun ago(ts: Long): String {
            val min = (Store.now() - ts) / 60
            return when {
                min < 1 -> "just now"
                min < 60 -> "$min min ago"
                min < 1440 -> "${Math.round(min / 60.0)} h ago"
                Math.round(min / 1440.0) == 1L -> "1 day ago"
                else -> "${Math.round(min / 1440.0)} days ago"
            }
        }
    }
}
