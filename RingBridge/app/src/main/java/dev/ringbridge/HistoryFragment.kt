package dev.ringbridge

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.ringbridge.databinding.FragmentHistoryBinding
import dev.ringbridge.db.RingDatabase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Wire row clicks → MetricDetailActivity
        fun rowClick(row: LinearLayout, type: String, label: String) {
            row.setOnClickListener {
                startActivity(
                    Intent(requireContext(), MetricDetailActivity::class.java)
                        .putExtra("type", type)
                        .putExtra("label", label)
                )
            }
        }
        rowClick(binding.rowHr,      "hr",            "Heart Rate")
        rowClick(binding.rowSpo2,    "spo2",          "SpO₂")
        rowClick(binding.rowBp,      "systolic",      "Blood Pressure")
        rowClick(binding.rowHrv,     "hrv",           "HRV")
        rowClick(binding.rowStress,  "stress",        "Stress")
        rowClick(binding.rowGlucose, "blood_glucose", "Blood Glucose")
        rowClick(binding.rowResp,    "resp_rate",     "Resp Rate")
        rowClick(binding.rowSteps,   "steps",         "Steps")
        rowClick(binding.rowBattery, "battery",       "Battery")
        rowClick(binding.rowSleep,   "sleep",         "Sleep")

        // Live readings → update "last" column
        viewLifecycleOwner.lifecycleScope.launch {
            RingService.readings.collect { map ->
                // Skip view work while this tab is hidden (see VitalsFragment).
                if (!isHidden) updateLastValues(map)
            }
        }

        // Seed last-known values from DB for metrics that only publish when > 0.
        // Without this, steps shows — until the user has walked at least 1 step today.
        viewLifecycleOwner.lifecycleScope.launch {
            val dao = RingDatabase.get(requireContext()).readings()
            val liveMap = RingService.readings.value
            // Only seed from DB if not already in the live readings map
            listOf("steps", "hr", "spo2", "systolic", "hrv", "stress", "blood_glucose", "resp_rate", "battery")
                .filter { liveMap[it] == null }
                .forEach { type ->
                    val r = dao.getLatest(type) ?: return@forEach
                    // Reuse updateLastValues by building a minimal map
                    updateLastValues(liveMap + (type to (r.value to r.timestamp)))
                }
        }

        // Sleep last value from DB
        viewLifecycleOwner.lifecycleScope.launch {
            val latest = RingDatabase.get(requireContext()).sleepSessions().getLatest()
            if (latest != null) {
                val h = latest.totalSleepMs / 3_600_000L
                val m = (latest.totalSleepMs % 3_600_000L) / 60_000L
                binding.tvSleepLast.text = if (h > 0) "${h}h ${m}m" else "${m}m"
            }
        }

        // Seed immediately
        updateLastValues(RingService.readings.value)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) updateLastValues(RingService.readings.value)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Last-value column ─────────────────────────────────────────────────────

    private fun updateLastValues(map: Map<String, Pair<Double, Long>>) {
        fun set(view: TextView, type: String, decimals: Int = 0, suffix: String = "") {
            val v = map[type]?.first ?: return
            view.text = (if (decimals == 0) v.toLong().toString() else "%.${decimals}f".format(v)) + suffix
        }

        set(binding.tvHrLast,      "hr",            suffix = " bpm")
        set(binding.tvSpo2Last,    "spo2",          suffix = "%")
        set(binding.tvHrvLast,     "hrv",           suffix = " ms")
        set(binding.tvStressLast,  "stress")
        set(binding.tvGlucoseLast, "blood_glucose", decimals = 1, suffix = " mmol/L")
        set(binding.tvRespLast,    "resp_rate",     decimals = 1, suffix = " brpm")
        set(binding.tvStepsLast,   "steps")
        set(binding.tvBatteryLast, "battery",       suffix = "%")

        // BP composite
        val sys = map["systolic"]?.first
        val dia = map["diastolic"]?.first
        if (sys != null && dia != null) {
            binding.tvBpLast.text = "${sys.toLong()}/${dia.toLong()}"
        }
    }
}
