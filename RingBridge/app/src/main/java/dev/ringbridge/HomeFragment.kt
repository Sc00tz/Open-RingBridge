package dev.ringbridge

import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.ringbridge.databinding.FragmentHomeBinding
import dev.ringbridge.databinding.ViewMetricCardBinding
import dev.ringbridge.db.RingDatabase
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /**
     * Static description of each metric card: which sensor type it shows, its label,
     * unit, accent colour, the detail-screen type to open on tap, and how to format
     * the latest value. Drives both binding and the tap-through to MetricDetailActivity.
     */
    private data class MetricSpec(
        val type: String,
        val label: String,
        val unit: String,
        val accentRes: Int,
        val detailType: String,
        val detailLabel: String,
        val format: (Double) -> String = { it.toLong().toString() },
    )

    /** Bind a MetricSpec to one of the included card layouts. */
    private fun cardBinding(card: View) = ViewMetricCardBinding.bind(card)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnToggle.setOnClickListener { toggleService() }

        // Readiness strip → HRV history (its primary input)
        binding.cardReadiness.isClickable = true
        binding.cardReadiness.isFocusable = true
        binding.cardReadiness.setOnClickListener {
            openDetail("hrv", "Readiness")
        }

        // Apply static label/unit/accent/tap to each card once.
        for ((card, spec) in cards()) {
            val b = cardBinding(card)
            b.metricLabel.text = spec.label
            b.metricUnit.text = spec.unit
            val accent = ContextCompat.getColor(requireContext(), spec.accentRes)
            b.metricDot.backgroundTintList = ColorStateList.valueOf(accent)
            b.metricSparkline.accentColor = accent
            b.root.setOnClickListener { openDetail(spec.detailType, spec.detailLabel) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            RingService.state.collect { state -> if (!isHidden) updateStatusCard(state) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            RingService.statusLabel.collect { label ->
                if (!isHidden && label.isNotEmpty()) binding.tvStatus.text = label
            }
        }
        // Live values update the card hero numbers (skip work while hidden).
        viewLifecycleOwner.lifecycleScope.launch {
            RingService.readings.collect { map -> if (!isHidden) updateCardValues(map) }
        }
        // Readiness recomputes only when HRV changes.
        viewLifecycleOwner.lifecycleScope.launch {
            RingService.readings
                .map { it["hrv"]?.first }
                .distinctUntilChanged()
                .collect { hrv -> if (!isHidden) refreshReadiness(hrv) }
        }

        // Initial paint from current state + DB.
        updateCardValues(RingService.readings.value)
        refreshReadiness(RingService.readings.value["hrv"]?.first)
        refreshSparklines()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            updateCardValues(RingService.readings.value)
            refreshReadiness(RingService.readings.value["hrv"]?.first)
            refreshSparklines()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Card specs ────────────────────────────────────────────────────────────

    /** Pairs each included card view with its spec. Order matches the grid layout. */
    private fun cards(): List<Pair<View, MetricSpec>> = listOf(
        binding.cardHr.root to MetricSpec("hr", "Heart Rate", "bpm", R.color.accent_hr, "hr", "Heart Rate"),
        binding.cardSpo2.root to MetricSpec("spo2", "SpO₂", "%", R.color.accent_spo2, "spo2", "SpO₂"),
        binding.cardBp.root to MetricSpec("systolic", "Blood Pressure", "mmHg", R.color.accent_bp, "systolic", "Blood Pressure"),
        binding.cardHrv.root to MetricSpec("hrv", "HRV", "ms", R.color.accent_hrv, "hrv", "HRV"),
        binding.cardStress.root to MetricSpec("stress", "Stress", "", R.color.accent_stress, "stress", "Stress"),
        binding.cardGlucose.root to MetricSpec("blood_glucose", "Blood Glucose", "mmol/L", R.color.accent_glucose, "blood_glucose", "Blood Glucose", { "%.1f".format(it) }),
        binding.cardSteps.root to MetricSpec("steps", "Steps", "", R.color.accent_steps, "steps", "Steps", { "%,d".format(it.toLong()) }),
        binding.cardBattery.root to MetricSpec("battery", "Battery", "%", R.color.accent_battery, "battery", "Battery"),
    )

    // ── Service control ─────────────────────────────────────────────────────────

    private fun toggleService() {
        if (RingService.state.value == RingService.State.IDLE) requestPermissionsAndStart()
        else RingService.stop(requireContext())
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) RingService.start(requireContext())
        else binding.tvStatus.text = "Bluetooth permission required"
    }

    private fun requestPermissionsAndStart() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) RingService.start(requireContext())
        else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openDetail(type: String, label: String) {
        startActivity(
            android.content.Intent(requireContext(), MetricDetailActivity::class.java)
                .putExtra("type", type)
                .putExtra("label", label)
        )
    }

    // ── Status card ───────────────────────────────────────────────────────────

    private fun updateStatusCard(state: RingService.State) {
        val (label, colorRes) = when (state) {
            RingService.State.IDLE         -> "Idle"          to R.color.status_idle
            RingService.State.SCANNING     -> "Scanning…"     to R.color.status_scanning
            RingService.State.CONNECTING   -> "Connecting…"   to R.color.status_connecting
            RingService.State.HANDSHAKING  -> "Handshaking…"  to R.color.status_connecting
            RingService.State.STREAMING    -> "Streaming ✓"   to R.color.status_streaming
            RingService.State.RECONNECTING -> "Reconnecting…" to R.color.status_reconnecting
        }
        binding.tvStatus.text = label
        binding.cardStatus.setCardBackgroundColor(ContextCompat.getColor(requireContext(), colorRes))
        binding.btnToggle.text = if (state == RingService.State.IDLE) "Start" else "Stop"
    }

    // ── Card hero values ──────────────────────────────────────────────────────

    private fun updateCardValues(map: Map<String, Pair<Double, Long>>) {
        for ((card, spec) in cards()) {
            val b = cardBinding(card)
            if (spec.type == "systolic") {
                // BP card shows sys/dia composite.
                val sys = map["systolic"]?.first
                val dia = map["diastolic"]?.first
                b.metricValue.text = if (sys != null && dia != null)
                    "${sys.toLong()}/${dia.toLong()}" else "—"
            } else {
                val v = map[spec.type]?.first
                b.metricValue.text = if (v != null) spec.format(v) else "—"
            }
        }
    }

    // ── Sparklines (from DB history) ────────────────────────────────────────────

    private fun refreshSparklines() {
        viewLifecycleOwner.lifecycleScope.launch {
            val dao = RingDatabase.get(requireContext()).readings()
            val since = System.currentTimeMillis() - 24 * 3_600_000L
            for ((card, spec) in cards()) {
                // BP sparkline tracks systolic; battery/steps trend over the day too.
                val series = dao.getHistory(spec.type, since)
                    .map { it.value.toFloat() }
                    .let { if (it.size > 40) it.takeLast(40) else it }   // cap points for a clean preview
                val b = cardBinding(card)
                if (series.size >= 3) {
                    b.metricSparkline.values = series
                    b.metricSparkline.visibility = View.VISIBLE
                } else {
                    b.metricSparkline.visibility = View.GONE
                }
            }
        }
    }

    // ── Readiness ─────────────────────────────────────────────────────────────

    private fun refreshReadiness(hrv: Double?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val since = System.currentTimeMillis() - 24 * 3_600_000L
            val db = RingDatabase.get(requireContext())
            val restingHr = db.readings().getMin("hr", since)
            val latestSleep = db.sleepSessions().getLatest()
            val score = ReadinessEngine.compute(hrv, restingHr, latestSleep)
            updateReadinessCard(score, hrv, restingHr, latestSleep)
        }
    }

    private fun updateReadinessCard(
        score: ReadinessEngine.Score,
        hrv: Double?,
        restingHr: Double?,
        sleep: dev.ringbridge.db.SleepSession?,
    ) {
        if (_binding == null) return
        binding.gaugeReadiness.score = score.total

        binding.tvReadinessLabel.text = if (score.total < 0) "—" else "${score.total} · ${score.label}"

        val colorRes = when {
            score.total < 0   -> null
            score.total >= 70 -> R.color.status_good
            score.total >= 50 -> R.color.status_caution
            else              -> R.color.status_alert
        }
        binding.tvReadinessLabel.setTextColor(
            ContextCompat.getColor(requireContext(), colorRes ?: android.R.color.darker_gray)
        )

        // Sub-line: HRV · RHR · sleep duration, omitting whichever is missing.
        val parts = mutableListOf<String>()
        hrv?.let { parts += "HRV ${it.toLong()}" }
        restingHr?.let { parts += "RHR ${it.toLong()}" }
        sleep?.takeIf { it.totalSleepMs > 0 }?.let {
            val h = it.totalSleepMs / 3_600_000L
            val m = (it.totalSleepMs % 3_600_000L) / 60_000L
            parts += if (h > 0) "${h}h ${m}m" else "${m}m"
        }
        binding.tvReadinessSub.text = parts.joinToString("  ·  ")
    }
}
