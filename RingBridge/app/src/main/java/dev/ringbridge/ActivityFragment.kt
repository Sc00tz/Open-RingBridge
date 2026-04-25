package dev.ringbridge

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.ringbridge.databinding.FragmentActivityBinding
import dev.ringbridge.db.RingDatabase
import dev.ringbridge.db.SleepSession
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActivityFragment : Fragment() {

    private var _binding: FragmentActivityBinding? = null
    private val binding get() = _binding!!

    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentActivityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Cards → history drill-down
        binding.cardSteps.isClickable = true
        binding.cardSteps.isFocusable = true
        binding.cardSteps.setOnClickListener {
            startActivity(
                Intent(requireContext(), MetricDetailActivity::class.java)
                    .putExtra("type", "steps")
                    .putExtra("label", "Steps")
            )
        }

        binding.cardSleep.isClickable = true
        binding.cardSleep.isFocusable = true
        binding.cardSleep.setOnClickListener {
            startActivity(
                Intent(requireContext(), MetricDetailActivity::class.java)
                    .putExtra("type", "sleep")
                    .putExtra("label", "Sleep")
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            RingService.readings.collect { map ->
                updateSteps(map)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            RingService.latestSleep.collect { session ->
                updateSleep(session)
            }
        }

        // Prime steps from DB on first load — live data only arrives when steps > 0,
        // which means a fresh day (or first connect) shows — until the user walks.
        // Show the last-known reading from DB so there's always something visible.
        viewLifecycleOwner.lifecycleScope.launch {
            if (RingService.readings.value["steps"] == null) {
                val latest = RingDatabase.get(requireContext()).readings().getLatest("steps")
                if (latest != null) updateStepsValues(latest.value, latest.timestamp)
            }
        }

        // Prime sleep from DB on first load
        viewLifecycleOwner.lifecycleScope.launch {
            val latest = RingDatabase.get(requireContext()).sleepSessions().getLatest()
            updateSleep(latest)
        }

        // Seed from current live state immediately (covers reconnect case)
        updateSteps(RingService.readings.value)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Steps ─────────────────────────────────────────────────────────────────

    private fun updateSteps(map: Map<String, Pair<Double, Long>>) {
        val (steps, ts) = map["steps"] ?: return
        updateStepsValues(steps, ts)
        map["distance_m"]?.first?.let { m ->
            val km = m / 1000.0
            binding.tvDistanceValue.text = if (km >= 1.0) "%.2f km".format(km) else "%.0f m".format(m)
        }
        map["calories"]?.first?.let { cal ->
            binding.tvCaloriesValue.text = "%.0f kcal".format(cal)
        }
    }

    private fun updateStepsValues(steps: Double, ts: Long) {
        binding.tvStepsValue.text = "%,d".format(steps.toLong())
        binding.tvStepsTime.text  = fmt.format(Date(ts))

        val goal = Settings.stepsGoal(requireContext())
        binding.tvStepsGoal.text       = "/ %,d".format(goal)
        binding.progressSteps.max      = goal
        binding.progressSteps.progress = steps.toLong().coerceAtMost(goal.toLong()).toInt()
    }

    // ── Sleep ─────────────────────────────────────────────────────────────────

    private fun updateSleep(session: SleepSession?) {
        if (session == null) return

        fun fmtDur(ms: Long): String {
            val h = ms / 3_600_000L
            val m = (ms % 3_600_000L) / 60_000L
            return if (h > 0) "${h}h ${m}m" else "${m}m"
        }

        binding.tvSleepDuration.text = fmtDur(session.totalSleepMs)

        val parts = mutableListOf<String>()
        if (session.deepSleepMs  > 0) parts += "Deep ${fmtDur(session.deepSleepMs)}"
        if (session.remSleepMs   > 0) parts += "REM ${fmtDur(session.remSleepMs)}"
        if (session.lightSleepMs > 0) parts += "Light ${fmtDur(session.lightSleepMs)}"
        binding.tvSleepBreakdown.text = if (parts.isNotEmpty()) parts.joinToString("  ·  ") else "—"
        binding.tvSleepTime.text      = fmt.format(Date(session.startMs))
    }
}
