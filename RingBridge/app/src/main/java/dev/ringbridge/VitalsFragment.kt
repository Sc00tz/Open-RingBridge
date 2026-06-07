package dev.ringbridge

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.ringbridge.databinding.FragmentVitalsBinding
import dev.ringbridge.databinding.ViewVitalsCardBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VitalsFragment : Fragment() {

    private var _binding: FragmentVitalsBinding? = null
    private val binding get() = _binding!!

    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** Static per-card config: type, label, unit, accent, detail target, value formatter. */
    private data class VitalSpec(
        val type: String,
        val label: String,
        val unit: String,
        val accentRes: Int,
        val detailType: String,
        val detailLabel: String,
        val decimals: Int = 0,
    )

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

        // Apply static label/unit/accent/tap once per card.
        for ((card, spec) in cards()) {
            val b = ViewVitalsCardBinding.bind(card)
            b.vitalLabel.text = spec.label
            b.vitalUnit.text = spec.unit
            val accent = ContextCompat.getColor(requireContext(), spec.accentRes)
            b.vitalDot.backgroundTintList = ColorStateList.valueOf(accent)
            b.root.setOnClickListener {
                startActivity(
                    Intent(requireContext(), MetricDetailActivity::class.java)
                        .putExtra("type", spec.detailType)
                        .putExtra("label", spec.detailLabel)
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            RingService.readings.collect { map -> if (!isHidden) updateCards(map) }
        }
        updateCards(RingService.readings.value)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) updateCards(RingService.readings.value)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Card specs ──────────────────────────────────────────────────────────────

    private fun cards(): List<Pair<View, VitalSpec>> = listOf(
        binding.cardHr.root      to VitalSpec("hr", "Heart Rate", "bpm", R.color.accent_hr, "hr", "Heart Rate"),
        binding.cardSpo2.root    to VitalSpec("spo2", "SpO₂", "%", R.color.accent_spo2, "spo2", "SpO₂"),
        binding.cardBp.root      to VitalSpec("systolic", "Blood Pressure", "mmHg", R.color.accent_bp, "systolic", "Blood Pressure"),
        binding.cardBattery.root to VitalSpec("battery", "Battery", "%", R.color.accent_battery, "battery", "Battery"),
        binding.cardGlucose.root to VitalSpec("blood_glucose", "Blood Glucose", "mmol/L", R.color.accent_glucose, "blood_glucose", "Blood Glucose", decimals = 1),
        binding.cardHrv.root     to VitalSpec("hrv", "HRV", "ms", R.color.accent_hrv, "hrv", "HRV"),
        binding.cardStress.root  to VitalSpec("stress", "Stress", "/ 100", R.color.accent_stress, "stress", "Stress"),
        binding.cardResp.root    to VitalSpec("resp_rate", "Respiratory Rate", "brpm", R.color.accent_resp, "resp_rate", "Resp Rate", decimals = 1),
    )

    // ── Card updates ──────────────────────────────────────────────────────────

    private fun updateCards(map: Map<String, Pair<Double, Long>>) {
        for ((card, spec) in cards()) {
            val b = ViewVitalsCardBinding.bind(card)
            if (spec.type == "systolic") {
                val sys = map["systolic"]?.first
                val dia = map["diastolic"]?.first
                val ts  = map["systolic"]?.second
                if (sys != null && dia != null) {
                    b.vitalValue.text = "${sys.toLong()}/${dia.toLong()}"
                    if (ts != null) b.vitalTime.text = fmt.format(Date(ts))
                    statusColor("systolic", sys)?.let { b.vitalValue.setTextColor(it) }
                }
            } else {
                val pair = map[spec.type] ?: continue
                val (v, ts) = pair
                b.vitalValue.text = if (spec.decimals == 0) v.toLong().toString()
                                    else "%.${spec.decimals}f".format(v)
                b.vitalTime.text = fmt.format(Date(ts))
                statusColor(spec.type, v)?.let { b.vitalValue.setTextColor(it) }
            }
        }
    }

    /** Green / amber / red zone color for a metric value, or null if no zoning defined. */
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
}
