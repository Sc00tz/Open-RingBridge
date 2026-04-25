package dev.ringbridge

import android.content.pm.PackageManager
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
import dev.ringbridge.db.RingDatabase
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) RingService.start(requireContext())
        else binding.tvStatus.text = "Bluetooth permission required"
    }

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

        // Make readiness card clickable → history
        binding.cardReadiness.isClickable = true
        binding.cardReadiness.isFocusable = true
        binding.cardReadiness.setOnClickListener {
            startActivity(
                android.content.Intent(requireContext(), MetricDetailActivity::class.java)
                    .putExtra("type", "hrv")
                    .putExtra("label", "Readiness")
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            RingService.state.collect { state ->
                updateStatusCard(state)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            RingService.statusLabel.collect { label ->
                if (label.isNotEmpty()) binding.tvStatus.text = label
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            RingService.readings.collect { map ->
                updateMiniTiles(map)
                refreshReadiness(map["hrv"]?.first)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            RingService.latestSleep.collect { session ->
                refreshReadiness(RingService.readings.value["hrv"]?.first)
            }
        }

        // Prime readiness from DB on first load
        viewLifecycleOwner.lifecycleScope.launch {
            refreshReadiness(RingService.readings.value["hrv"]?.first)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Service control ───────────────────────────────────────────────────────

    private fun toggleService() {
        if (RingService.state.value == RingService.State.IDLE) {
            requestPermissionsAndStart()
        } else {
            RingService.stop(requireContext())
        }
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

    // ── Mini tiles ────────────────────────────────────────────────────────────

    private fun updateMiniTiles(map: Map<String, Pair<Double, Long>>) {
        map["hr"]?.first?.let      { binding.tvHrMini.text   = it.toLong().toString() }
        map["spo2"]?.first?.let    { binding.tvSpo2Mini.text = it.toLong().toString() }
        map["battery"]?.first?.let { binding.tvBattMini.text = it.toLong().toString() }
    }

    // ── Readiness ─────────────────────────────────────────────────────────────

    private fun refreshReadiness(hrv: Double?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val since = System.currentTimeMillis() - 24 * 3_600_000L
            val restingHr = RingDatabase.get(requireContext())
                .readings()
                .getMin("hr", since)
            val latestSleep = RingDatabase.get(requireContext())
                .sleepSessions()
                .getLatest()
            val score = ReadinessEngine.compute(hrv, restingHr, latestSleep)
            updateReadinessCard(score)
        }
    }

    private fun updateReadinessCard(score: ReadinessEngine.Score) {
        binding.gaugeReadiness.score = score.total
        binding.tvReadinessLabel.text = score.label

        val colorRes = when {
            score.total < 0   -> null
            score.total >= 70 -> R.color.status_good
            score.total >= 50 -> R.color.status_caution
            else              -> R.color.status_alert
        }
        if (colorRes != null) {
            binding.tvReadinessLabel.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
        } else {
            binding.tvReadinessLabel.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
        }
    }
}
