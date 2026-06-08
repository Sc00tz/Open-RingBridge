package dev.ringbridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.ringbridge.RingProtocol.SensorEvent
import dev.ringbridge.db.RingDatabase
import dev.ringbridge.db.SensorReading
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nordicsemi.android.ble.ktx.suspend

/**
 * Foreground service that owns the BLE connection to the R01L ring.
 *
 * BLE transport is handled by [RingBleManager] (Nordic BLE Library).
 * This class handles the YCBT application protocol: handshake, history, keepalive,
 * sensor dispatch, and HA sync.
 */
@SuppressLint("MissingPermission")
class RingService : LifecycleService() {

    enum class State { IDLE, SCANNING, CONNECTING, HANDSHAKING, STREAMING, RECONNECTING }

    companion object {
        private const val TAG = "RingService"

        private const val NOTIF_CHANNEL   = "ringbridge"
        private const val NOTIF_ID_STATUS = 1
        private const val NOTIF_ID_DISC   = 2

        // Reconnect backoff: 5 s → 10 s → 30 s → 60 s → 60 s forever
        private val BACKOFF_MS = longArrayOf(5_000, 10_000, 30_000, 60_000)

        private val _state          = MutableStateFlow(State.IDLE)
        private val _statusLabel    = MutableStateFlow("")
        private val _readings       = MutableStateFlow<Map<String, Pair<Double, Long>>>(emptyMap())
        private val _latestSleep    = MutableStateFlow<dev.ringbridge.db.SleepSession?>(null)
        /** Bumped to currentTimeMillis() each time pullHistory() completes. Observers re-query the DB. */
        private val _historyPulled  = MutableStateFlow(0L)
        /**
         * True while MainActivity is visible. Controls streaming mode:
         *  - Foreground: START_REAL_COMPREHENSIVE active (1 Hz sensor stream)
         *  - Background: streaming stopped; ring uses its autonomous 5-min monitoring.
         *    This halves ring and phone battery drain and allows the ring's sleep-staging
         *    algorithm to run (it can't run while the MCU is busy streaming at 1 Hz).
         */
        private val _appForeground  = MutableStateFlow(false)

        val state          = _state.asStateFlow()
        val statusLabel    = _statusLabel.asStateFlow()
        val readings       = _readings.asStateFlow()
        val latestSleep    = _latestSleep.asStateFlow()
        val historyPulled  = _historyPulled.asStateFlow()

        /** Called from MainActivity.onResume / onPause. */
        fun setForeground(v: Boolean) { _appForeground.value = v }

        fun start(context: Context) =
            context.startForegroundService(Intent(context, RingService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, RingService::class.java))
    }

    private lateinit var publisher:      ServerPublisher
    private lateinit var network:        NetworkMonitor
    private lateinit var bleManager:     RingBleManager
    private lateinit var screenReceiver: BroadcastReceiver

    private val adapter get() =
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var connectionJob:    Job? = null
    private var streamingJob:     Job? = null   // cancelled + restarted on each connection
    private var hrvTimerJob:      Job? = null   // 3-min countdown to first HRV measurement
    private var reconnectCount:   Int  = 0
    private var keepaliveCounter: Int  = 1
    private var lastPruneMs:      Long = 0L
    private var lastHrvMs:        Long = System.currentTimeMillis()   // init to now so HRV doesn't fire immediately on first connect
    private var lastRealMs:       Long = 0L   // last time AppControlReal was sent
    private var hrvMeasuring:     Boolean = false
    private val hist = HistoryState()

    // Per-type DB write throttle.
    // 0x060A fires ~1/sec — without throttling that's 86 400 rows/day per metric.
    // Event-driven types not listed here (battery, distance_m, calories) always write through.
    private val DB_THROTTLE_MS = mapOf(
        "hr"            to  60_000L,   //  1 min  — very high frequency
        "resp_rate"     to  60_000L,   //  1 min
        "steps"         to  60_000L,   //  1 min
        "wearing_state" to  60_000L,   //  1 min
        "spo2"          to 600_000L,   // 10 min  — changes slowly
        "blood_glucose" to 900_000L,   // 15 min
        "systolic"      to 300_000L,   //  5 min  — BP from 0x060A background stream
        "diastolic"     to 300_000L,   //  5 min
        "hrv"           to 300_000L,   //  5 min
        "stress"        to 300_000L,   //  5 min
    )
    private val dbLastSave = mutableMapOf<String, Long>()

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        publisher  = ServerPublisher(this)
        network    = NetworkMonitor(this)
        bleManager = RingBleManager(this)

        bleManager.onC1C3 = { bytes ->
            RingProtocol.parsePacket(bytes)?.let { handlePacket(it) }
        }
        bleManager.onHr = { bytes -> handleHr(bytes) }

        // Use screen state rather than Activity lifecycle to detect "user asleep" vs
        // "user awake". onPause/onResume are triggered by dozens of transient things
        // (system dialogs, notification peeks, etc.) and are unreliable for this purpose.
        // Screen OFF  → stop streaming so ring enters autonomous sleep-staging mode.
        // User present (unlock) → start streaming again.
        val pm = getSystemService(PowerManager::class.java)
        _appForeground.value = pm.isInteractive   // true if screen is currently on

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.i(TAG, "Screen off — stopping stream, ring enters autonomous mode")
                        setForeground(false)
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        // USER_PRESENT fires when keyguard is dismissed (phone unlocked).
                        // ACTION_SCREEN_ON fires on every notification wake — we don't want
                        // to start streaming for a 3-second notification glance at 2 AM.
                        Log.i(TAG, "User present (unlocked) — starting stream")
                        setForeground(true)
                    }
                }
            }
        }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID_STATUS,
                buildStatusNotification("Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID_STATUS, buildStatusNotification("Starting…"))
        }

        network.onConnected = {
            lifecycleScope.launch { publisher.flush(Settings.wifiOnly(this@RingService)) }
        }
        network.start()

        lifecycleScope.launch {
            _latestSleep.value = RingDatabase.get(this@RingService).sleepSessions().getLatest()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (connectionJob == null || connectionJob?.isActive == false) {
            connectionJob = lifecycleScope.launch { connectLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        network.stop()
        connectionJob?.cancel()
        bleManager.close()
        _state.value = State.IDLE
        super.onDestroy()
    }

    // ── Connection loop ───────────────────────────────────────────────────────

    private suspend fun connectLoop() {
        reconnectCount = 0
        while (currentCoroutineContext().isActive) {

            if (!adapter.isEnabled) {
                _state.value = State.SCANNING
                updateNotification("Waiting for Bluetooth…")
                Log.d(TAG, "Bluetooth disabled — retrying in 10 s")
                delay(10_000)
                continue
            }

            try {
                _state.value = State.SCANNING
                updateNotification("Scanning for ring…")
                Log.d(TAG, "Scanning for ${RingProtocol.DEVICE_NAME}…")

                // 30 s timeout: if the ring doesn't advertise (e.g. phantom-connected after
                // app reinstall) withTimeoutOrNull returns null so we fall through to the
                // reconnect backoff and retry — instead of hanging in Scanning forever.
                // NOTE: must use withTimeoutOrNull, NOT withTimeout — TimeoutCancellationException
                // is a CancellationException and would be rethrown by the catch block below,
                // killing the entire connectLoop.
                val device = withTimeoutOrNull(30_000) { scanForDevice() }
                if (device == null) {
                    Log.w(TAG, "Scan timed out — ring not found after 30 s, will retry")
                    updateNotification("Ring not found — retrying…")
                    // Don't go through the normal disconnect / notify flow; just continue
                    // the while loop (which will re-enter SCANNING after the backoff delay).
                    _state.value = State.RECONNECTING
                    val backoffMs = BACKOFF_MS.getOrElse(reconnectCount) { BACKOFF_MS.last() }
                    reconnectCount++
                    delay(backoffMs)
                    continue
                }

                Log.i(TAG, "Found ${device.name} [${device.address}]")
                _state.value = State.CONNECTING
                updateNotification("Connecting…")

                runProtocol(device)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Connection error: ${e.message}")
            }

            try { bleManager.disconnect().enqueue() } catch (_: Exception) {}

            _state.value = State.RECONNECTING
            if (!Settings.reconnectSilent(this@RingService)) showDisconnectNotification()

            val backoffMs = BACKOFF_MS.getOrElse(reconnectCount) { BACKOFF_MS.last() }
            reconnectCount++
            Log.d(TAG, "Reconnecting in ${backoffMs / 1000} s (attempt $reconnectCount)")
            delay(backoffMs)
        }
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    private suspend fun scanForDevice(): BluetoothDevice = suspendCancellableCoroutine { cont ->
        val scanner = adapter.bluetoothLeScanner ?: run {
            cont.cancel(Exception("Bluetooth scanner unavailable (BT off?)"))
            return@suspendCancellableCoroutine
        }

        // BALANCED mode: duty-cycles the radio, finds the ring in 1-2 s vs 0.5 s for LOW_LATENCY.
        // The extra second is irrelevant; the phone radio savings during repeated reconnects are not.
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED).build()

        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(type: Int, result: android.bluetooth.le.ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: "(null)"
                Log.d(TAG, "BLE device: $name  [${result.device.address}]  rssi=${result.rssi}")

                if (name.startsWith(RingProtocol.DEVICE_NAME)) {
                    scanner.stopScan(this)
                    Log.i(TAG, "Ring found!")
                    if (cont.isActive) cont.resumeWith(Result.success(result.device))
                }
            }
            override fun onScanFailed(errorCode: Int) {
                if (cont.isActive) cont.cancel(Exception("Scan failed: $errorCode"))
            }
        }
        scanner.startScan(emptyList(), settings, cb)
        cont.invokeOnCancellation { try { scanner.stopScan(cb) } catch (_: Exception) {} }
    }

    // ── Protocol ──────────────────────────────────────────────────────────────

    private suspend fun runProtocol(device: BluetoothDevice) {
        bleManager.connect(device)
            .timeout(15_000)
            .useAutoConnect(false)
            .retry(0)
            .suspend()

        Log.i(TAG, "Connected and subscribed — starting handshake")
        _state.value = State.HANDSHAKING
        updateNotification("Handshaking…")

        suspend fun cmd(label: String, pkt: ByteArray, ms: Long = 400) {
            updateNotification(label)
            Log.i(TAG, "[>>] $label")
            bleManager.send(pkt)
            delay(ms)
        }

        // Handshake sequence — mirrors confirmed working session
        cmd("GetPowerStats",       RingProtocol.GET_POWER_STATS)
        cmd("GetSupportFunction",  RingProtocol.GET_SUPPORT_FUNCTION)
        cmd("SettingTime",         RingProtocol.settingTime())
        cmd("GetChipScheme",       RingProtocol.GET_CHIP_SCHEME)
        cmd("GetDeviceInfo",       RingProtocol.GET_DEVICE_INFO)
        delay(3_000)
        cmd("GetChipScheme 2",     RingProtocol.GET_CHIP_SCHEME)
        cmd("GetDeviceName",       RingProtocol.GET_DEVICE_NAME)
        cmd("GetDeviceInfo 2",     RingProtocol.GET_DEVICE_INFO)
        cmd("GetDeviceInfo 3",     RingProtocol.GET_DEVICE_INFO)
        cmd("AppControlReal STOP", RingProtocol.STOP_REAL)
        cmd("GetDeviceInfo 4",     RingProtocol.GET_DEVICE_INFO)
        cmd("Cmd0109",             RingProtocol.CMD_0109)
        cmd("GetDeviceInfo 5",     RingProtocol.GET_DEVICE_INFO)
        cmd("SetHeartMonitor",     RingProtocol.SETTING_HEART_MONITOR)
        cmd("SetSpO2Monitor",      RingProtocol.SETTING_SPO2_MONITOR)
        cmd("GetDeviceInfo 6",     RingProtocol.GET_DEVICE_INFO)
        cmd("GetNowStep",          RingProtocol.GET_NOW_STEP)
        cmd("GetPowerStats 2",     RingProtocol.GET_POWER_STATS)
        cmd("Keepalive 0",         RingProtocol.keepalive(0))
        delay(1_000)

        _state.value = State.STREAMING
        reconnectCount = 0
        updateNotification("Connected")
        Log.i(TAG, "Connected — foreground=${_appForeground.value}")

        if (_appForeground.value) {
            // Start streaming immediately — ring serves its cached BP/SpO2/HR readings
            // in the first few 0x060A packets. History is deferred to the first keepalive
            // tick (~30 s) so the optical sensor is never cold-started on connect.
            startStreaming()
            hist.pending = true
        } else {
            // Background connect: nothing to interrupt, pull history now.
            pullHistory()
        }

        // One-shot snapshot: supplements the stream with any values the ring already has.
        // Sent after streaming commands so the ring is in active mode when it responds.
        lifecycleScope.launch {
            delay(2_000)
            bleManager.send(RingProtocol.GET_ALL_REAL_DATA)
            bleManager.send(RingProtocol.GET_REAL_SPO2)
        }

        // React to screen on/off for the lifetime of this connection.
        // Skip the first emission (already handled by the explicit block above).
        streamingJob?.cancel()
        streamingJob = lifecycleScope.launch {
            var firstEmission = true
            _appForeground.collect { foreground ->
                if (firstEmission) { firstEmission = false; return@collect }
                if (!bleManager.isConnected) return@collect
                if (foreground) {
                    startStreaming()   // fresh HRV timer on every screen-on
                    if (!hist.pulling) hist.pending = true
                } else {
                    stopStreaming()    // cancels HRV timer; ring enters autonomous mode
                    // Safe to pull history now — optical sensor is already paused.
                    if (hist.pending && !hist.pulling) {
                        lifecycleScope.launch { pullHistory() }
                    }
                }
            }
        }

        // ── Keepalive + renewal loop ──────────────────────────────────────────
        // 30 s interval: enough to keep the link alive; 15 s was unnecessarily frequent.
        while (bleManager.isConnected) {
            delay(30_000)
            if (!bleManager.isConnected) break

            bleManager.send(RingProtocol.keepalive(keepaliveCounter++))

            val now = System.currentTimeMillis()
            val foreground = _appForeground.value

            // AppControlReal renewal: only needed while streaming (foreground mode).
            // AppControlReal auto-expires after ~160 s; renewing it keeps the 1-Hz stream alive.
            // In background we intentionally let it expire — ring reverts to 5-min autonomous
            // monitoring, which cuts ring and phone power draw dramatically.
            if (foreground && now - lastRealMs >= 120_000) {
                bleManager.send(RingProtocol.START_REAL)
                bleManager.send(RingProtocol.START_REAL_COMPREHENSIVE)
                bleManager.send(RingProtocol.START_REAL_RESP)
                lastRealMs = now
                Log.d(TAG, "AppControlReal renewed (foreground)")
            }

            // HRV: only trigger when app is visible (it suppresses all sensors for 90 s) and
            // only every 2 hours — 30 min was 48 ECG sessions/day and killed the ring battery.
            if (foreground && !hrvMeasuring && now - lastHrvMs >= 120 * 60_000L) {
                lifecycleScope.launch { triggerHrvMeasurement() }
            }

            if (hist.pending && !hist.pulling) {
                if (_appForeground.value) {
                    // Foreground: pull immediately, preserving the HRV countdown.
                    stopStreaming()
                    pullHistory()
                    startStreaming(newHrvTimer = false)
                } else {
                    // Background (screen off): the ring will keep signalling history pending
                    // on every keepalive. Pulling every 30 s all night constantly interrupts
                    // the ring's autonomous sleep-staging and drains its battery.
                    // Suppress the pull here — it will be triggered when the screen comes back
                    // on (streamingJob collector sets hist.pending = true on foreground→true).
                    Log.d(TAG, "Background: deferring history pull until screen-on")
                    hist.pending = false
                }
            }

            // Prune old synced readings once per day
            if (now - lastPruneMs >= 86_400_000L) {
                lastPruneMs = now
                lifecycleScope.launch { publisher.prune() }
            }
        }

        Log.d(TAG, "Ring disconnected — exiting runProtocol")
    }

    // ── Streaming control ─────────────────────────────────────────────────────

    /**
     * Start the 1-Hz comprehensive sensor stream.
     *
     * @param newHrvTimer  When true (default), cancels any existing HRV countdown and starts
     *   a fresh 3-minute timer. Pass false when restarting streaming after a mid-session
     *   history pull — the timer is already running and we don't want to reset it.
     */
    private fun startStreaming(newHrvTimer: Boolean = true) {
        bleManager.send(RingProtocol.START_REAL)
        bleManager.send(RingProtocol.START_REAL_COMPREHENSIVE)
        bleManager.send(RingProtocol.START_REAL_RESP)
        lastRealMs = System.currentTimeMillis()
        Log.i(TAG, "Real-time streaming started")
        updateNotification("Streaming")

        if (newHrvTimer) {
            // Cancel any existing countdown so screen-off/on cycles don't stack timers.
            hrvTimerJob?.cancel()
            hrvTimerJob = lifecycleScope.launch {
                delay(3 * 60_000L)
                triggerHrvMeasurement()
            }
        }
    }

    /**
     * Stop the sensor stream. Called when app goes to background.
     * The ring reverts to its autonomous 5-min monitoring mode, which is far
     * lower power and allows the ring's sleep-staging algorithm to run.
     */
    private fun stopStreaming() {
        hrvTimerJob?.cancel()
        hrvTimerJob = null
        bleManager.send(RingProtocol.STOP_REAL)
        Log.i(TAG, "Real-time streaming stopped — ring in autonomous mode")
        updateNotification("Connected (background)")
    }

    // ── On-demand HRV measurement ─────────────────────────────────────────────

    private suspend fun triggerHrvMeasurement() {
        // Don't trigger in background — CONTROL_WAVE_START suppresses all sensor
        // notifications for up to 90 s, and HRV is an interactive foreground feature.
        if (hrvMeasuring || !bleManager.isConnected || !_appForeground.value) return
        hrvMeasuring = true
        lastHrvMs    = System.currentTimeMillis()
        Log.i(TAG, "[HRV] Starting emotional measurement")
        bleManager.send(RingProtocol.EMOTIONAL_START)
        delay(200)
        bleManager.send(RingProtocol.CONTROL_WAVE_START)

        // Safety timeout — always stop wave so SpO2/HR notifications resume
        delay(90_000)
        if (hrvMeasuring && bleManager.isConnected) {
            Log.w(TAG, "[HRV] Timeout — stopping wave")
            bleManager.send(RingProtocol.CONTROL_WAVE_STOP)
            hrvMeasuring = false
        }
    }

    // ── History pull ──────────────────────────────────────────────────────────

    private suspend fun pullHistory() {
        if (hist.pulling) return
        hist.pulling = true
        hist.pending = false

        // We request and ACK each category, but DELIBERATELY DO NOT DELETE it from the
        // ring afterward. The ring manages its own ring-buffer and overwrites the oldest
        // records when full, so deletion is not required to keep syncing. Deleting was
        // destructive: a single sync wiped the ring's only copy, and if the local DB was
        // ever cleared before that data reached the server, it was gone for good. Reads
        // are idempotent — re-pulling the same records is harmless because the DB upserts
        // on (timestamp, type). (DELETE_SPORT/SLEEP/HEART/BLOOD remain defined in
        // RingProtocol for reference but are intentionally never sent.)
        data class Category(
            val requestCmd: ByteArray,
            val name:       String,
        )
        val categories = listOf(
            Category(RingProtocol.GET_SPORT_HISTORY, "SportHistory"),
            Category(RingProtocol.GET_SLEEP_HISTORY, "SleepHistory"),
            Category(RingProtocol.GET_HEART_HISTORY, "HeartHistory"),
            Category(RingProtocol.GET_BLOOD_HISTORY, "BloodHistory"),
        )

        for (cat in categories) {
            hist.reset()
            updateNotification("Syncing ${cat.name}…")
            Log.i(TAG, "[Sync] ${cat.name}…")
            bleManager.send(cat.requestCmd)

            // 30 s timeout — BLE can be slow with large datasets.
            withTimeoutOrNull(30_000) {
                while (!hist.complete) delay(100)
            }

            // ACK so the ring exits transfer mode, even if we timed out.
            // NOTE: ACK only — no delete. The ring keeps its history (see above).
            if (hist.ringDone || hist.buffer.isNotEmpty()) {
                bleManager.send(RingProtocol.HISTORY_ACK)
                delay(300)
            }

            val buf = hist.buffer
            if (buf.isEmpty()) continue

            when (cat.name) {
                "SleepHistory" -> {
                    val decoded = RingProtocol.decodeSleepHistory(buf)
                    Log.i(TAG, "SleepHistory: ${decoded.size} sessions decoded")
                    if (decoded.isNotEmpty()) {
                        val db = RingDatabase.get(this)
                        val sessions = decoded.map { it.session }
                        db.sleepSessions().insertAll(sessions)
                        val stages = decoded.flatMap { it.stages }
                        if (stages.isNotEmpty()) db.sleepStages().insertAll(stages)
                        _latestSleep.value = sessions.maxByOrNull { it.startMs }
                    }
                }
                else -> {
                    val records = when (cat.name) {
                        "SportHistory" -> RingProtocol.decodeSportHistory(buf)
                        "HeartHistory" -> RingProtocol.decodeHeartHistory(buf)
                        "BloodHistory" -> RingProtocol.decodeBloodHistory(buf)
                        else           -> emptyList()
                    }
                    Log.i(TAG, "${cat.name}: ${records.size} records decoded")

                    // Hydrate the dashboard with the most recent non-zero value per metric.
                    // Skip "steps" from sport history — it would overwrite the live
                    // cumulative step count that the ring is actively maintaining.
                    val latestByType = mutableMapOf<String, Pair<Double, Long>>()
                    for (rec in records) {
                        for ((type, value) in rec.values) {
                            if (type == "steps") continue
                            if (value <= 0) continue
                            val existing = latestByType[type]
                            if (existing == null || rec.timestampMs > existing.second) {
                                latestByType[type] = value to rec.timestampMs
                            }
                        }
                    }
                    latestByType.forEach { (type, pair) ->
                        lifecycleScope.launch { publishSensor(type, pair.first) }
                    }

                    val dbRows = records.flatMap { rec ->
                        rec.values
                            .filter { (_, v) -> v > 0 }
                            .map { (k, v) ->
                                SensorReading(timestamp = rec.timestampMs, type = k, value = v)
                            }
                    }
                    if (dbRows.isNotEmpty()) {
                        RingDatabase.get(this).readings().insertAll(dbRows)
                    }
                }
            }
            delay(300)
        }

        lifecycleScope.launch { publisher.flush(Settings.wifiOnly(this@RingService)) }
        hist.pulling = false
        hist.reset()
        _historyPulled.value = System.currentTimeMillis()   // signal charts to refresh
        Log.i(TAG, "History pull done")
    }

    // ── Incoming data handlers ────────────────────────────────────────────────

    private fun handlePacket(pkt: RingProtocol.Packet) {
        val (datatype, payload, sensor) = pkt
        // Log raw packets at DEBUG only — 0x060A fires ~1/sec and would flood INFO logs
        Log.d(TAG, "[<<] ${"%04X".format(datatype)}: ${payload.toHex()}")

        // 0x060A — UploadComprehensive: real-time ~1 Hz.
        // Merge the sensor dispatch and field extraction into ONE coroutine launch per packet.
        // Previously two nested launches were created every second = 172k coroutine allocs/day.
        // Also batch all field updates into a single StateFlow write to reduce UI recompositions.
        if (datatype == 0x060A && payload.size >= 16) {
            // No gate condition here — on the R01L, HR/SpO2/BP come via dedicated notifications
            // (0x0601, 0x0602, history), so those bytes in 0x060A are always 0. Gating on them
            // drops every packet, including glucose at byte[20] which IS populated.
            // Individual field checks below handle zeros — no top-level gate needed.
            lifecycleScope.launch {
                sensor?.let { dispatchSensor(it) }   // handles any non-null sensor events

                val steps = payload[0].u() or (payload[1].u() shl 8) or (payload[2].u() shl 16)
                val hr    = payload[7].u()
                val sbp   = payload[8].u()
                val dbp   = payload[9].u()
                val spo2  = payload[10].u()
                val resp  = payload[11].u()
                val worn  = payload[14].u()
                val batt  = payload[15].u()

                // Build all updates at once → one StateFlow write instead of 7-8
                val batch = buildMap<String, Double> {
                    if (steps > 0) put("steps",         steps.toDouble())
                    if (hr    > 0) put("hr",            hr.toDouble())
                    if (sbp   > 0) put("systolic",      sbp.toDouble())
                    if (dbp   > 0) put("diastolic",     dbp.toDouble())
                    if (spo2  > 0) put("spo2",          spo2.toDouble())
                    if (resp  > 0) put("resp_rate",     resp.toDouble())
                    if (batt  > 0) put("battery",       batt.toDouble())
                    put("wearing_state", if (worn == 1) 1.0 else 0.0)
                    if (payload.size >= 21) {
                        val glucose = payload[20].u()
                        if (glucose != 0 && glucose != 15) put("blood_glucose", glucose / 10.0)
                    }
                }
                if (batch.isNotEmpty()) publishSensorBatch(batch)
            }
            // Fall through to when() block for control-packet handling
        } else {
            sensor?.let { lifecycleScope.launch { dispatchSensor(it) } }
        }

        when (datatype) {
            0x0220 -> {                         // GetAllRealDataFromDevice — snapshot of all current values
                // Layout from DataUnpack.unpackGetAllRealDataFromDevice():
                //   [0]=HR  [1]=SBP  [2]=DBP  [3]=SpO2  [4]=resp
                //   [7..9]=steps(LE24)  [10..11]=calories  [12..13]=distance
                if (payload.size >= 5) {
                    lifecycleScope.launch {
                        val hr   = payload[0].u(); if (hr   > 0) publishSensor("hr",       hr.toDouble())
                        val sbp  = payload[1].u(); if (sbp  > 0) publishSensor("systolic", sbp.toDouble())
                        val dbp  = payload[2].u(); if (dbp  > 0) publishSensor("diastolic",dbp.toDouble())
                        val spo2 = payload[3].u(); if (spo2 > 0) publishSensor("spo2",     spo2.toDouble())
                        val resp = payload[4].u(); if (resp > 0) publishSensor("resp_rate",resp.toDouble())
                        if (payload.size >= 10) {
                            val steps = payload[7].u() or (payload[8].u() shl 8) or (payload[9].u() shl 16)
                            if (steps > 0) publishSensor("steps", steps.toDouble())
                        }
                    }
                }
            }
            0x0211 -> {                         // manual SpO2 poll result
                if (payload.size >= 2 && payload[0].u() == 1) {
                    lifecycleScope.launch { publishSensor("spo2", payload[1].u().toDouble()) }
                }
            }
            0x0613 -> {                         // WearingStatus change (worn ↔ unworn)
                if (payload.size >= 5) {
                    lifecycleScope.launch {
                        publishSensor("wearing_state", if (payload[4].u() == 1) 1.0 else 0.0)
                    }
                }
            }
            0x0603 -> {
                // dispatchSensor() already publishes systolic, diastolic, and HR from
                // SensorEvent.BloodPressure. Only handle the extra fields here: HRV and SpO2.
                if (payload.size >= 4 && payload[3].u() > 0) {
                    lifecycleScope.launch { publishSensor("hrv", payload[3].u().toDouble()) }
                }
                if (payload.size >= 5 && payload[4].u() > 0) {
                    lifecycleScope.launch { publishSensor("spo2", payload[4].u().toDouble()) }
                }
            }
            0x032F -> {                         // keepalive ack — payload[0]==1 means history pending
                if (payload.isNotEmpty() && payload[0] == 0x01.toByte()) {
                    hist.pending = true
                    Log.i(TAG, "Keepalive ack: history pending")
                }
            }
            0x040E -> {                         // device-initiated: history ready
                hist.pending = true
                Log.i(TAG, "0x040E: history ready")
            }
            0x0502, 0x0504, 0x0506, 0x0508 -> { // history meta (Sport=0x0502, Sleep=0x0504, Heart=0x0506, Blood=0x0508)
                if (payload.size >= 8) {
                    hist.expectedBytes =
                        (payload[6].toInt() and 0xFF) or ((payload[7].toInt() and 0xFF) shl 8)
                    Log.i(TAG, "History meta ${"0x%04X".format(datatype)}: ${hist.expectedBytes} B expected")
                    if (hist.expectedBytes == 0) hist.complete = true
                } else if (payload.size == 1 && (payload[0].u() and 0xF0) == 0xF0) {
                    Log.w(TAG, "History ${"0x%04X".format(datatype)}: unsupported (0x${"%02X".format(payload[0])})")
                    hist.complete = true
                } else if (payload.isEmpty()) {
                    hist.complete = true
                } else {
                    // Ring returns [0x00, 0x00] (2 bytes) when a category has no history data.
                    // Without this branch the handler falls through with no action → 30 s timeout.
                    // Source: 5-15-2026-logs.log line 139 — `[<<] 0504: 0000`
                    Log.i(TAG, "History ${"0x%04X".format(datatype)}: no data (${payload.size}-byte meta, treating as empty)")
                    hist.complete = true
                }
            }
            0x0511, 0x0513, 0x0515, 0x0517 -> { // history bulk data (Sport=0x0511, Sleep=0x0513, Heart=0x0515, Blood=0x0517)
                hist.append(payload)
                hist.receivedBytes += payload.size
                if (hist.expectedBytes > 0 && hist.receivedBytes >= hist.expectedBytes) {
                    hist.ringDone = true
                    hist.complete = true  // unblock the wait loop; don't wait for 0x0580
                }
            }
            0x0580 -> {                         // ring signals transfer complete (may arrive after complete)
                hist.ringDone = true
                hist.complete = true
                // ACK is now sent by pullHistory() after the timeout block; skip duplicate send
            }
        }
    }

    private fun handleHr(bytes: ByteArray) {
        if (bytes.size < 2) return
        val flags = bytes[0].toInt()
        val bpm = if (flags and 0x01 != 0) {
            if (bytes.size < 3) return
            (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
        } else {
            bytes[1].toInt() and 0xFF
        }
        // Ring sends bpm=0 when optical sensor has no lock. Never publish zero.
        if (bpm <= 0) return
        // During HRV ECG measurement the ring reports ECG-derived HR on the standard BLE
        // characteristic, which spikes well above the resting optical reading. Suppress it —
        // the HRV result (0x0610) will update HR via dispatchSensor when measurement completes.
        if (hrvMeasuring) return
        lifecycleScope.launch { publishSensor("hr", bpm.toDouble()) }
    }

    // ── Sensor dispatch ───────────────────────────────────────────────────────

    private suspend fun dispatchSensor(event: SensorEvent) {
        when (event) {
            is SensorEvent.HeartRate     -> { if (event.bpm > 0) publishSensor("hr", event.bpm.toDouble()) }
            is SensorEvent.SpO2          -> publishSensor("spo2", event.percent.toDouble())
            is SensorEvent.BloodPressure -> {
                // All three fields come from 0x0603[0..2]. Publish all of them here so
                // the 0x0603 block in handlePacket only needs to cover HRV/SpO2/temp extras.
                if (event.systolic  > 0) publishSensor("systolic",  event.systolic.toDouble())
                if (event.diastolic > 0) publishSensor("diastolic", event.diastolic.toDouble())
                if (event.hr        > 0) publishSensor("hr",        event.hr.toDouble())
            }
            is SensorEvent.Steps         -> {
                if (event.count    > 0) publishSensor("steps",      event.count.toDouble())
                if (event.distanceM > 0) publishSensor("distance_m", event.distanceM.toDouble())
                if (event.calories > 0) publishSensor("calories",   event.calories.toDouble())
            }
            is SensorEvent.RespiratoryRate -> publishSensor("resp_rate", event.bpm.toDouble())
            is SensorEvent.HRV           -> {
                if (event.hrv_ms > 0) publishSensor("hrv",    event.hrv_ms.toDouble())
                publishSensor("stress", event.stress.toDouble())
                // Stop the ECG wave as soon as a valid HRV result arrives
                if (hrvMeasuring && event.hrv_ms > 0) {
                    Log.i(TAG, "[HRV] Got result hrv=${event.hrv_ms} — stopping wave")
                    bleManager.send(RingProtocol.CONTROL_WAVE_STOP)
                    hrvMeasuring = false
                }
            }
            is SensorEvent.Battery       -> publishSensor("battery", event.percent.toDouble())
        }
    }

    private suspend fun publishSensor(type: String, value: Double) {
        val now = System.currentTimeMillis()
        _readings.value = _readings.value + (type to Pair(value, now))

        val throttleMs = DB_THROTTLE_MS[type]
        val shouldPersist = if (throttleMs != null) {
            now - (dbLastSave[type] ?: 0L) >= throttleMs
        } else true

        if (shouldPersist) {
            dbLastSave[type] = now
            publisher.publish(type, value)
        }
        Log.d(TAG, "Sensor: $type = $value")
    }

    /**
     * Batch variant of [publishSensor] for high-frequency packets (0x060A at 1 Hz).
     *
     * All [values] are written to [_readings] in a *single* StateFlow update, which means
     * one UI recomposition instead of N separate ones per packet. DB throttle logic and
     * server publish are unchanged per-metric.
     */
    private suspend fun publishSensorBatch(values: Map<String, Double>) {
        val now = System.currentTimeMillis()

        // Build the new readings map in one pass, then assign once
        val updated = _readings.value.toMutableMap()
        for ((type, value) in values) updated[type] = value to now
        _readings.value = updated

        for ((type, value) in values) {
            val throttleMs = DB_THROTTLE_MS[type]
            val shouldPersist = if (throttleMs != null) {
                now - (dbLastSave[type] ?: 0L) >= throttleMs
            } else true
            if (shouldPersist) {
                dbLastSave[type] = now
                publisher.publish(type, value)
            }
        }
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            NOTIF_CHANNEL, "RingBridge", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Ring connection status" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildStatusNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("RingBridge")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        _statusLabel.value = text
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID_STATUS, buildStatusNotification(text))
    }

    private fun showDisconnectNotification() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Ring disconnected")
            .setContentText("Reconnecting…")
            .setContentIntent(pi)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID_DISC, n)
    }

    // ── History state ─────────────────────────────────────────────────────────

    private class HistoryState {
        var pending:       Boolean = false
        var pulling:       Boolean = false
        var expectedBytes: Int     = 0
        var receivedBytes: Int     = 0
        var ringDone:      Boolean = false
        var complete:      Boolean = false

        // ByteArrayOutputStream avoids the O(n²) memory behaviour of ByteArray +=.
        // Each write() appends in-place; toByteArray() produces one final copy at decode time.
        var stream = java.io.ByteArrayOutputStream()

        // Convenience accessor so call sites can still use hist.buffer
        val buffer: ByteArray get() = stream.toByteArray()

        fun append(payload: ByteArray) {
            stream.write(payload)
        }

        fun reset() {
            expectedBytes = 0
            receivedBytes = 0
            ringDone      = false
            complete      = false
            stream        = java.io.ByteArrayOutputStream()
        }
    }

    private fun Byte.u() = toInt() and 0xFF
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
