package dev.ringbridge

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import dev.ringbridge.databinding.ActivityMetricDetailBinding
import dev.ringbridge.db.RingDatabase
import dev.ringbridge.db.SensorReading
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MetricDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMetricDetailBinding
    private lateinit var type: String
    private lateinit var label: String

    /** Anchor for relative X-axis timestamps (ms offset from first point). */
    private var baseTime = 0L

    /** Currently selected time window — kept so history-pull refreshes use the same range. */
    private var currentHours = 6

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

    // ── Per-metric config ─────────────────────────────────────────────────────

    /**
     * Minimum valid value for each metric type.
     * Readings below this are considered sensor-not-ready artifacts and excluded from the chart.
     */
    private val MIN_VALID = mapOf(
        "hr"            to 30.0,   // resting HR below 30 is not plausible
        "spo2"          to 50.0,   // SpO2 below 50 is hardware artifact
        "systolic"      to 50.0,
        "diastolic"     to 30.0,
        "hrv"           to 1.0,
        "stress"        to 1.0,
        "resp_rate"     to 2.0,
        "blood_glucose" to 1.0,
        "steps"         to -1.0,   // 0 steps is valid (start of day reset)
        "battery"       to 1.0,
        "sleep"         to 0.0,
    )

    /** Max gap (ms) between consecutive readings before the line is broken. */
    private val GAP_THRESHOLD_MS = mapOf(
        "hr"            to 10 * 60_000L,   // break after 10 min gap
        "spo2"          to 30 * 60_000L,
        "systolic"      to 30 * 60_000L,
        "diastolic"     to 30 * 60_000L,
        "hrv"           to 60 * 60_000L,
        "stress"        to 60 * 60_000L,
        "resp_rate"     to 30 * 60_000L,
        "blood_glucose" to 60 * 60_000L,
        "steps"         to 2 * 60_000L,
        "battery"       to 60 * 60_000L,
        "sleep"         to 36 * 60 * 60_000L,  // break only if > 36 h gap between sessions
    )

    /** Reference lines to draw on the Y axis (optional). */
    data class RefLine(val value: Float, val label: String, val color: Int)

    private val REFERENCE_LINES: Map<String, List<RefLine>> = mapOf(
        "hr"    to listOf(
            RefLine(60f,  "60",  Color.parseColor("#4443A047")),
            RefLine(100f, "100", Color.parseColor("#44E53935")),
        ),
        "spo2"  to listOf(
            RefLine(95f,  "95%",  Color.parseColor("#4443A047")),
            RefLine(92f,  "92%",  Color.parseColor("#44E53935")),
        ),
        "systolic" to listOf(
            RefLine(120f, "120", Color.parseColor("#4443A047")),
            RefLine(140f, "140", Color.parseColor("#44E53935")),
        ),
        "stress" to listOf(
            RefLine(40f, "Low",  Color.parseColor("#4443A047")),
            RefLine(70f, "High", Color.parseColor("#44E53935")),
        ),
    )

    /** Accent colour for the line/fill, keyed by metric type. */
    private fun accentColor(): Int = when (type) {
        "hr"            -> ContextCompat.getColor(this, R.color.accent_hr)
        "spo2"          -> ContextCompat.getColor(this, R.color.accent_spo2)
        "systolic",
        "diastolic"     -> ContextCompat.getColor(this, R.color.accent_bp)
        "battery"       -> ContextCompat.getColor(this, R.color.accent_battery)
        "steps"         -> ContextCompat.getColor(this, R.color.accent_steps)
        "resp_rate"     -> ContextCompat.getColor(this, R.color.accent_resp)
        "hrv"           -> ContextCompat.getColor(this, R.color.accent_hrv)
        "stress"        -> ContextCompat.getColor(this, R.color.accent_stress)
        "blood_glucose" -> ContextCompat.getColor(this, R.color.accent_glucose)
        "sleep"         -> ContextCompat.getColor(this, R.color.accent_sleep)
        else            -> Color.parseColor("#FF4488DD")
    }

    // ── Activity lifecycle ────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMetricDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        type  = intent.getStringExtra("type")  ?: "hr"
        label = intent.getStringExtra("label") ?: "Metric"

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = label
        }

        setupChart()

        binding.chip6h.isChecked = true
        loadData(6)

        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentHours = when (checkedIds.firstOrNull()) {
                R.id.chip1h  -> 1
                R.id.chip6h  -> 6
                R.id.chip24h -> 24
                R.id.chip7d  -> 24 * 7
                else         -> 6
            }
            loadData(currentHours)
        }

        // Refresh chart whenever RingService finishes a history pull.
        // This backfills the gap created by a disconnection without requiring the user to
        // switch time ranges or reopen the screen.
        lifecycleScope.launch {
            RingService.historyPulled.collectLatest { ts ->
                if (ts > 0) when (type) {
                    "sleep"    -> loadSleepData()
                    "systolic" -> loadBpData(System.currentTimeMillis() - currentHours * 3_600_000L)
                    else       -> loadData(currentHours)
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── Chart setup ───────────────────────────────────────────────────────────

    private fun setupChart() {
        val chart = binding.chart
        chart.description.isEnabled  = false
        chart.legend.isEnabled       = false
        chart.setTouchEnabled(true)
        chart.isDragEnabled          = true
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.setDrawGridBackground(false)
        chart.setNoDataText("No readings in this time range")
        chart.setNoDataTextColor(Color.GRAY)
        chart.setExtraOffsets(8f, 16f, 8f, 8f)

        chart.xAxis.apply {
            position            = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            labelRotationAngle  = -35f
            granularity         = 1f
            textColor           = Color.GRAY
            textSize            = 10f
            valueFormatter      = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val ms = baseTime + value.toLong()
                    val spanMs = System.currentTimeMillis() - baseTime
                    return if (baseTime > 0 && spanMs > 12 * 3_600_000L)
                        dateFmt.format(Date(ms))
                    else
                        timeFmt.format(Date(ms))
                }
            }
        }

        chart.axisRight.isEnabled = false
        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor  = Color.parseColor("#22FFFFFF")
            textColor  = Color.GRAY
            textSize   = 10f
        }
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadData(hours: Int) {
        val since = System.currentTimeMillis() - hours * 3_600_000L
        if (type == "sleep") {
            loadSleepData()
            return
        }
        if (type == "systolic") {
            loadBpData(since)
            return
        }
        lifecycleScope.launch {
            val dao      = RingDatabase.get(this@MetricDetailActivity).readings()
            val raw      = dao.getHistory(type, since)
            val readings = filterReadings(raw)
            updateChart(readings)
            updateStats(readings)
        }
    }

    private fun loadBpData(since: Long) {
        lifecycleScope.launch {
            val dao = RingDatabase.get(this@MetricDetailActivity).readings()
            val sys = filterReadings(dao.getHistory("systolic",  since))
            val dia = filterReadings(dao.getHistory("diastolic", since))
            updateBpChart(sys, dia)
            updateBpStats(sys, dia)
        }
    }

    private fun loadSleepData() {
        // Hide time-range chips — they don't apply to sleep sessions
        binding.chipGroup.visibility = android.view.View.GONE

        lifecycleScope.launch {
            val db      = RingDatabase.get(this@MetricDetailActivity)
            val session = db.sleepSessions().getLatest() ?: run {
                updateSleepStats(null, emptyList())
                return@launch
            }
            val stages = db.sleepStages().getForSession(session.startMs)
            renderSleepTimeline(session, stages)
            updateSleepStats(session, stages)
        }
    }

    private fun renderSleepTimeline(
        session: dev.ringbridge.db.SleepSession,
        stages:  List<dev.ringbridge.db.SleepStage>,
    ) {
        val chart = binding.chart

        // Y-axis levels — Awake at top, Deep at bottom (visually matches Oura/Garmin style)
        fun stageY(type: Int): Float = when (type) {
            0xF1 -> 1f    // Deep
            0xF5 -> 1.5f  // Nap
            0xF2 -> 2f    // Light
            0xF3 -> 3f    // REM
            0xF4 -> 4f    // Awake
            else -> 2f
        }

        if (stages.isEmpty()) {
            chart.setNoDataText("No stage data recorded yet")
            chart.clear()
            chart.invalidate()
            return
        }

        baseTime = session.startMs
        val entries = mutableListOf<com.github.mikephil.charting.data.Entry>()

        for (stage in stages) {
            val x = (stage.timestamp - session.startMs).toFloat()
            entries += com.github.mikephil.charting.data.Entry(x, stageY(stage.stageType))
        }
        // Final point at session end so the last stage extends to its full duration
        val last = stages.last()
        val endX = (last.timestamp + last.durationSec * 1000L - session.startMs).toFloat()
        entries += com.github.mikephil.charting.data.Entry(endX, stageY(last.stageType))

        val color = accentColor()
        val dataset = com.github.mikephil.charting.data.LineDataSet(entries, label).apply {
            mode             = com.github.mikephil.charting.data.LineDataSet.Mode.STEPPED
            setDrawCircles(false)
            lineWidth        = 3f
            this.color       = color
            setDrawFilled(true)
            fillColor        = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
            fillAlpha        = 60
            setDrawValues(false)
            highLightColor   = Color.WHITE
        }

        chart.axisLeft.apply {
            axisMinimum    = 0.5f
            axisMaximum    = 4.5f
            granularity    = 1f
            setLabelCount(4, true)
            removeAllLimitLines()
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String = when (value.roundToInt()) {
                    1 -> "Deep"
                    2 -> "Light"
                    3 -> "REM"
                    4 -> "Awake"
                    else -> ""
                }
            }
        }

        // X axis: clock time labels
        chart.xAxis.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val ms = session.startMs + value.toLong()
                return timeFmt.format(java.util.Date(ms))
            }
        }

        chart.data = com.github.mikephil.charting.data.LineData(dataset)
        chart.animateX(400)
        chart.invalidate()
    }

    private fun updateSleepStats(
        session: dev.ringbridge.db.SleepSession?,
        stages:  List<dev.ringbridge.db.SleepStage>,
    ) {
        fun fmtDur(ms: Long): String {
            val h = ms / 3_600_000L
            val m = (ms % 3_600_000L) / 60_000L
            return if (h > 0) "${h}h ${m}m" else "${m}m"
        }

        if (session == null) {
            binding.tvMinLabel.text = "DEEP"
            binding.tvAvgLabel.text = "TOTAL"
            binding.tvMaxLabel.text = "REM"
            binding.tvMin.text = "—"
            binding.tvAvg.text = "—"
            binding.tvMax.text = "—"
            return
        }

        binding.tvMinLabel.text = "DEEP"
        binding.tvAvgLabel.text = "TOTAL"
        binding.tvMaxLabel.text = "REM"
        binding.tvMin.text = fmtDur(session.deepSleepMs)
        binding.tvAvg.text = fmtDur(session.totalSleepMs)
        binding.tvMax.text = fmtDur(session.remSleepMs)
    }

    private fun updateBpChart(sys: List<SensorReading>, dia: List<SensorReading>) {
        val chart = binding.chart
        if (sys.isEmpty() && dia.isEmpty()) {
            chart.clear(); chart.invalidate(); return
        }

        // Anchor baseTime to the earliest point across both series
        baseTime = minOf(
            sys.firstOrNull()?.timestamp ?: Long.MAX_VALUE,
            dia.firstOrNull()?.timestamp ?: Long.MAX_VALUE,
        )

        val sysColor = ContextCompat.getColor(this, R.color.accent_bp)
        val diaColor = ContextCompat.getColor(this, R.color.accent_hrv)  // distinct colour
        val gapMs    = GAP_THRESHOLD_MS["systolic"] ?: (30 * 60_000L)

        fun makeDatasets(readings: List<SensorReading>, color: Int, seriesLabel: String)
                : List<LineDataSet> {
            val segments = mutableListOf<List<Entry>>()
            var current  = mutableListOf<Entry>()
            for (i in readings.indices) {
                val r = readings[i]
                val x = (r.timestamp - baseTime).toFloat()
                if (i > 0 && (r.timestamp - readings[i - 1].timestamp) > gapMs) {
                    if (current.isNotEmpty()) segments += current
                    current = mutableListOf()
                }
                current += Entry(x, r.value.toFloat())
            }
            if (current.isNotEmpty()) segments += current
            val showDots = readings.size <= 60
            return segments.map { entries ->
                LineDataSet(entries, seriesLabel).apply {
                    setDrawCircles(showDots)
                    setDrawCircleHole(false)
                    circleRadius   = if (showDots) 3.5f else 2f
                    setCircleColor(color)
                    lineWidth      = 2f
                    this.color     = color
                    setDrawFilled(true)
                    fillColor      = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
                    fillAlpha      = 30
                    setDrawValues(false)
                    mode           = LineDataSet.Mode.CUBIC_BEZIER
                    cubicIntensity = 0.15f
                    highLightColor = Color.WHITE
                }
            }
        }

        val datasets = makeDatasets(sys, sysColor, "Systolic") +
                       makeDatasets(dia, diaColor, "Diastolic")

        // Y bounds spanning both series
        val allValues = (sys + dia).map { it.value.toFloat() }
        val dataMin = allValues.minOrNull() ?: 60f
        val dataMax = allValues.maxOrNull() ?: 140f
        val pad = ((dataMax - dataMin) * 0.15f).coerceAtLeast(5f)

        chart.axisLeft.apply {
            axisMinimum = (dataMin - pad).coerceAtLeast(50f)
            axisMaximum =  dataMax + pad
            removeAllLimitLines()
            REFERENCE_LINES["systolic"]?.forEach { ref ->
                addLimitLine(LimitLine(ref.value, ref.label).apply {
                    lineWidth  = 1f; lineColor = ref.color
                    textColor  = Color.GRAY; textSize = 9f
                    enableDashedLine(8f, 4f, 0f)
                })
            }
        }

        chart.legend.isEnabled = true
        chart.data = LineData(datasets)
        chart.animateX(300)
        chart.invalidate()
    }

    private fun updateBpStats(sys: List<SensorReading>, dia: List<SensorReading>) {
        binding.tvMinLabel.text = "DIA AVG"
        binding.tvAvgLabel.text = "SYS AVG"
        binding.tvMaxLabel.text = "SYS MAX"

        fun fmt(v: Double) = v.roundToInt().toString()

        if (sys.isEmpty()) {
            binding.tvMin.text = "—"; binding.tvAvg.text = "—"; binding.tvMax.text = "—"
            return
        }
        binding.tvAvg.text = fmt(sys.map { it.value }.average())
        binding.tvMax.text = fmt(sys.maxOf { it.value })
        binding.tvMin.text = if (dia.isNotEmpty()) fmt(dia.map { it.value }.average()) else "—"
    }

    /**
     * Strip out sensor-not-ready artifacts (zeros and sub-plausible values)
     * before plotting. This prevents the HR→0 spike during wave mode and
     * similar artifacts from other metrics.
     */
    private fun filterReadings(readings: List<SensorReading>): List<SensorReading> {
        val minValid = MIN_VALID[type] ?: 0.0
        return readings.filter { it.value > minValid }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    private fun updateStats(readings: List<SensorReading>) {
        if (readings.isEmpty()) {
            binding.tvMin.text = "—"
            binding.tvAvg.text = "—"
            binding.tvMax.text = "—"
            return
        }
        val values = readings.map { it.value }
        val min = values.min()
        val max = values.max()
        val avg = values.average()

        fun fmt(v: Double) = if (v == v.roundToInt().toDouble()) v.roundToInt().toString()
                             else "%.1f".format(v)

        binding.tvMin.text = fmt(min)
        binding.tvAvg.text = fmt(avg)
        binding.tvMax.text = fmt(max)
    }

    // ── Chart rendering ───────────────────────────────────────────────────────

    private fun updateChart(readings: List<SensorReading>) {
        val chart = binding.chart

        if (readings.isEmpty()) {
            chart.clear()
            chart.invalidate()
            return
        }

        baseTime = readings.first().timestamp
        val color  = accentColor()
        val fillRgb = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        val gapMs  = GAP_THRESHOLD_MS[type] ?: (30 * 60_000L)

        // Split into segments at gaps so the line isn't drawn across dead zones.
        // MPAndroidChart doesn't have native gap support, so we use multiple datasets.
        val segments = mutableListOf<List<Entry>>()
        var current  = mutableListOf<Entry>()

        for (i in readings.indices) {
            val r = readings[i]
            val x = (r.timestamp - baseTime).toFloat()
            if (i > 0 && (r.timestamp - readings[i - 1].timestamp) > gapMs) {
                if (current.isNotEmpty()) segments += current
                current = mutableListOf()
            }
            current += Entry(x, r.value.toFloat())
        }
        if (current.isNotEmpty()) segments += current

        val totalPoints = readings.size
        val showDots    = totalPoints <= 60   // dots only when sparse enough to be readable

        val datasets = segments.map { entries ->
            LineDataSet(entries, label).apply {
                setDrawCircles(showDots)
                setDrawCircleHole(false)
                circleRadius     = if (showDots) 3.5f else 2f
                setCircleColor(color)
                lineWidth        = 2f
                this.color       = color
                setDrawFilled(true)
                fillColor        = fillRgb
                fillAlpha        = 40
                setDrawValues(false)
                // Cubic bezier smoothing — looks much better than linear for bio data
                mode             = LineDataSet.Mode.CUBIC_BEZIER
                cubicIntensity   = 0.15f
                highLightColor   = Color.WHITE
            }
        }

        chart.data = LineData(datasets)

        // ── Y axis bounds — tight auto-scale with a small margin ──────────────
        val allValues = readings.map { it.value.toFloat() }
        val dataMin   = allValues.min()
        val dataMax   = allValues.max()
        val range     = (dataMax - dataMin).coerceAtLeast(1f)
        val pad       = (range * 0.15f).coerceAtLeast(
            when (type) {
                "spo2"   -> 1f
                "hr"     -> 5f
                else     -> 2f
            }
        )

        // For SpO2, never go below 85 or above 101 — keeps scale readable
        val yMin = when (type) {
            "spo2"   -> (dataMin - pad).coerceAtLeast(85f)
            "battery"-> 0f
            "steps"  -> 0f
            else     -> (dataMin - pad).coerceAtLeast(0f)
        }
        val yMax = when (type) {
            "spo2"   -> (dataMax + pad).coerceAtMost(101f)
            "battery"-> 100f
            else     -> dataMax + pad
        }

        chart.axisLeft.apply {
            axisMinimum = yMin
            axisMaximum = yMax
            removeAllLimitLines()
            REFERENCE_LINES[type]?.forEach { ref ->
                if (ref.value in yMin..yMax) {
                    addLimitLine(LimitLine(ref.value, ref.label).apply {
                        lineWidth    = 1f
                        lineColor    = ref.color
                        textColor    = Color.GRAY
                        textSize     = 9f
                        enableDashedLine(8f, 4f, 0f)
                    })
                }
            }
        }

        chart.animateX(300)
        chart.invalidate()
    }
}
