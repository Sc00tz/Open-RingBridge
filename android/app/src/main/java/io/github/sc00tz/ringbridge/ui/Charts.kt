package io.github.sc00tz.ringbridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sc00tz.ringbridge.data.Derived
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Charts drawn the way the web dashboard draws them: thin lines, recessive grid, the latest value labelled. */
data class Series(val name: String, val color: Color, val points: List<Pair<Long, Double>>)

private val HOUR = DateTimeFormatter.ofPattern("h a")
private val DAY = DateTimeFormatter.ofPattern("M/d")

private fun DrawScope.label(m: TextMeasurer, text: String, at: Offset, color: Color, size: Int = 11, bold: Boolean = false, alignRight: Boolean = false) {
    val layout = m.measure(text, TextStyle(fontSize = size.sp, color = color, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal))
    drawText(layout, topLeft = Offset(if (alignRight) at.x - layout.size.width else at.x, at.y - layout.size.height / 2f))
}

private fun niceRange(lo: Double, hi: Double): Pair<Double, Double> {
    if (lo == hi) return (lo - 1) to (hi + 1)
    val step = when (val span = hi - lo) { in 0.0..5.0 -> 1.0; in 5.0..25.0 -> 5.0; in 25.0..100.0 -> 10.0; else -> 10.0 * ceil(span / 100) }
    return floor(lo / step) * step to ceil(hi / step) * step
}

@Composable
fun LineChart(series: List<Series>, xMin: Long, xMax: Long, unit: String, days: Boolean = false, height: Int = 170) {
    val m = rememberTextMeasurer()
    val ink2 = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val all = series.flatMap { it.points }
    if (all.size < 2) return
    val (lo, hi) = niceRange(all.minOf { it.second }, all.maxOf { it.second })
    val last = series.firstOrNull()?.points?.lastOrNull()
    val zone = ZoneId.systemDefault()
    val fmt = if (days) DAY else HOUR
    Canvas(Modifier.fillMaxWidth().height(height.dp).semantics {
        contentDescription = "${series.joinToString { it.name }} chart, ${lo.toInt()} to ${hi.toInt()} $unit" + (last?.let { ", latest ${it.second.toInt()}" } ?: "")
    }) {
        val left = 34.dp.toPx(); val right = size.width - 30.dp.toPx(); val top = 8.dp.toPx(); val bottom = size.height - 20.dp.toPx()
        val x = { t: Long -> left + (right - left) * ((t - xMin).toFloat() / max(1L, xMax - xMin)) }
        val y = { v: Double -> (bottom - (bottom - top) * ((v - lo) / (hi - lo))).toFloat() }
        for (i in 0..2) {
            val v = lo + (hi - lo) * i / 2
            drawLine(grid, Offset(left, y(v)), Offset(right, y(v)), 1.dp.toPx())
            label(m, "%.0f".format(v), Offset(left - 6.dp.toPx(), y(v)), ink2, alignRight = true)
        }
        for (i in 0..2) {
            val t = xMin + (xMax - xMin) * i / 2
            val text = Instant.ofEpochSecond(t).atZone(zone).format(fmt)
            label(m, text, Offset(x(t) - if (i == 2) 0f else if (i == 1) 12.dp.toPx() else 0f, bottom + 11.dp.toPx()), ink2, alignRight = i == 2)
        }
        for (s in series) {
            val path = Path()
            var gapFrom: Long? = null
            s.points.forEachIndexed { i, (t, v) ->
                // Break the line across gaps over 40 minutes (or 2 days on day charts) rather than drawing across missing data.
                val gap = gapFrom != null && t - gapFrom!! > if (days) 2 * 86400 else 2400
                if (i == 0 || gap) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v))
                gapFrom = t
            }
            drawPath(path, s.color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        if (last != null) {
            val p = Offset(x(last.first), y(last.second))
            drawCircle(series.first().color, 4.dp.toPx(), p)
            label(m, "%.0f".format(last.second), Offset(p.x + 6.dp.toPx(), p.y), ink, bold = true)
        }
    }
}

@Composable
fun Columns(values: List<Double>, labels: List<String>, color: Color, unit: String, height: Int = 150) {
    val m = rememberTextMeasurer()
    val ink2 = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    if (values.isEmpty()) return
    val top = max(1.0, values.max())
    Canvas(Modifier.fillMaxWidth().height(height.dp).semantics { contentDescription = "Column chart, up to ${top.toInt()} $unit" }) {
        val left = 34.dp.toPx(); val bottom = size.height - 20.dp.toPx(); val right = size.width
        drawLine(grid, Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
        label(m, "%,.0f".format(top), Offset(left - 6.dp.toPx(), 8.dp.toPx()), ink2, alignRight = true)
        val slot = (right - left) / values.size
        val w = max(2.dp.toPx(), slot - 2.dp.toPx())
        values.forEachIndexed { i, v ->
            val h = ((bottom - 8.dp.toPx()) * (v / top)).toFloat()
            if (h > 0) drawRoundRect(color, Offset(left + i * slot + 1.dp.toPx(), bottom - h), Size(w, h), CornerRadius(min(w / 2, 4.dp.toPx())))
        }
        val every = max(1, ceil(values.size / 5.0).toInt())
        labels.forEachIndexed { i, l -> if (i % every == 0) label(m, l, Offset(left + i * slot, bottom + 11.dp.toPx()), ink2) }
    }
}

val STAGE_ROWS = listOf("awake" to "Awake", "rem" to "REM", "light" to "Light", "deep" to "Deep")

@Composable
fun Hypnogram(segments: List<Derived.Segment>, start: Long, end: Long, colors: Map<String, Color>) {
    val m = rememberTextMeasurer()
    val ink2 = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = "Sleep stages through the night" }) {
        val left = 48.dp.toPx(); val bottom = size.height - 20.dp.toPx(); val rowH = bottom / STAGE_ROWS.size
        val x = { t: Long -> left + (size.width - left) * ((t - start).toFloat() / max(1L, end - start)) }
        STAGE_ROWS.forEachIndexed { i, (_, name) -> label(m, name, Offset(0f, rowH * i + rowH / 2), ink2) }
        for (s in segments) {
            val row = STAGE_ROWS.indexOfFirst { it.first == s.stage }.takeIf { it >= 0 } ?: continue
            val x0 = x(s.startTs)
            val x1 = max(x0 + 1.dp.toPx(), x(s.startTs + s.seconds))
            drawRoundRect(colors[s.stage] ?: Color.Gray, Offset(x0, rowH * row + 3.dp.toPx()), Size(x1 - x0, rowH - 6.dp.toPx()), CornerRadius(3.dp.toPx()))
        }
        val zone = ZoneId.systemDefault()
        label(m, Instant.ofEpochSecond(start).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a")), Offset(left, bottom + 11.dp.toPx()), ink2)
        label(m, Instant.ofEpochSecond(end).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a")), Offset(size.width, bottom + 11.dp.toPx()), ink2, alignRight = true)
    }
}

data class Band(val name: String, val from: Int, val to: Int, val color: Color)

/** A half-circle gauge. With no value, it's drawn empty in gray rather than showing a false zero. */
@Composable
fun Gauge(value: Int?, bands: List<Band>, description: String) {
    val m = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurface
    val empty = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = description }) {
        val r = min(size.width / 2, size.height) * 0.86f
        val c = Offset(size.width / 2, size.height - 8.dp.toPx())
        val stroke = 18.dp.toPx()
        val box = Size(r * 2, r * 2)
        val tl = Offset(c.x - r, c.y - r)
        for (b in bands) {
            val startAngle = 180f + 180f * b.from / 100f
            val sweep = 180f * (b.to - b.from) / 100f - if (b.to < 100) 1.5f else 0f
            drawArc(if (value == null) empty else b.color, startAngle, sweep, false, tl, box, style = Stroke(stroke))
        }
        if (value != null) {
            val a = PI * (1 + value / 100.0)
            val inner = r - stroke / 2 - 4.dp.toPx()
            val tip = Offset(c.x + (inner * cos(a)).toFloat(), c.y + (inner * sin(a)).toFloat())
            val base = Offset(c.x + ((inner - 14.dp.toPx()) * cos(a)).toFloat(), c.y + ((inner - 14.dp.toPx()) * sin(a)).toFloat())
            drawLine(ink, base, tip, 3.dp.toPx(), StrokeCap.Round)
        }
        val layout = m.measure(value?.toString() ?: "–", TextStyle(fontSize = 44.sp, fontWeight = FontWeight.SemiBold, color = ink))
        drawText(layout, topLeft = Offset(c.x - layout.size.width / 2, c.y - layout.size.height - 2.dp.toPx()))
    }
}
