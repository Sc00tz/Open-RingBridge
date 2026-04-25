package dev.ringbridge

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import dev.ringbridge.databinding.FragmentVitalsBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VitalsFragment : Fragment() {

    private var _binding: FragmentVitalsBinding? = null
    private val binding get() = _binding!!

    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVitalsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Card → history drill-down
        fun metricClick(card: MaterialCardView, type: String, label: String) {
            card.isClickable = true
            card.isFocusable = true
            card.setOnClickListener {
                startActivity(
                    Intent(requireContext(), MetricDetailActivity::class.java)
                        .putExtra("type", type)
                        .putExtra("label", label)
                )
            }
        }
        metricClick(binding.cardHr,      "hr",            "Heart Rate")
        metricClick(binding.cardSpo2,    "spo2",          "SpO₂")
        metricClick(binding.cardBp,      "systolic",      "Blood Pressure")
        metricClick(binding.cardBattery, "battery",       "Battery")
        metricClick(binding.cardGlucose, "blood_glucose", "Blood Glucose")
        metricClick(binding.cardHrv,     "hrv",           "HRV")
        metricClick(binding.cardStress,  "stress",        "Stress")
        metricClick(binding.cardResp,    "resp_rate",     "Resp Rate")

        viewLifecycleOwner.lifecycleScope.launch {
            RingService.readings.collect { map ->
                updateCards(map)
            }
        }

        // Seed with current values immediately
        updateCards(RingService.readings.value)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Card updates ──────────────────────────────────────────────────────────

    private fun statusColor(type: String, value: Double): Int? {
        val res = when (type) {
            "hr"      -> when {
                value < 50 || value > 100 -> R.color.status_alert
                value <= 80               -> R.color.status_good
                else                      -> R.color.status_caution
            }
            "spo2"    -> when {
                value >= 95 -> R.color.status_good
                value >= 92 -> R.color.status_caution
                else        -> R.color.status_alert
            }
            "hrv"     -> when {
                value >= 50 -> R.color.status_good
                value >= 30 -> R.color.status_caution
                else        -> R.color.status_alert
            }
            "stress"  -> when {
                value <= 40 -> R.color.status_good
                value <= 70 -> R.color.status_caution
                else        -> R.color.status_alert
            }
            "battery" -> when {
                value >= 50 -> R.color.status_good
                value >= 20 -> R.color.status_caution
                else        -> R.color.status_alert
            }
            "systolic" -> when {
                value < 120 -> R.color.status_good
                value < 140 -> R.color.status_caution
                else        -> R.color.status_alert
            }
            else -> null
        }
        return res?.let { ContextCompat.getColor(requireContext(), it) }
    }

    private fun tile(
        type: String,
        valueView: TextView,
        timeView: TextView,
        map: Map<String, Pair<Double, Long>>,
        decimals: Int = 0
    ) {
        val (v, ts) = map[type] ?: return
        valueView.text = if (decimals == 0) v.toLong().toString() else "%.${decimals}f".format(v)
        timeView.text  = fmt.format(Date(ts))
        statusColor(type, v)?.let { valueView.setTextColor(it) }
    }

    private fun updateCards(map: Map<String, Pair<Double, Long>>) {
        tile("hr",            binding.tvHrValue,      binding.tvHrTime,      map)
        tile("spo2",          binding.tvSpo2Value,    binding.tvSpo2Time,    map)
        tile("hrv",           binding.tvHrvValue,     binding.tvHrvTime,     map)
        tile("stress",        binding.tvStressValue,  binding.tvStressTime,  map)
        tile("battery",       binding.tvBatteryValue, binding.tvBatteryTime, map)
        tile("blood_glucose", binding.tvGlucoseValue, binding.tvGlucoseTime, map, decimals = 1)
        tile("resp_rate",     binding.tvRespValue,    binding.tvRespTime,    map, decimals = 1)

        // BP composite
        val sys  = map["systolic"]?.first
        val dia  = map["diastolic"]?.first
        val bpTs = map["systolic"]?.second
        if (sys != null && dia != null && bpTs != null) {
            binding.tvBpValue.text = "${sys.toLong()}/${dia.toLong()}"
            binding.tvBpTime.text  = fmt.format(Date(bpTs))
            statusColor("systolic", sys)?.let { binding.tvBpValue.setTextColor(it) }
        }
    }
}
