package dev.ringbridge

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Semicircular gauge that displays the readiness score (0–100).
 *
 * Arc zones (low→high is good):
 *   0–50   red    → Low
 *   50–70  amber  → Fair
 *   70–85  green  → Good
 *   85–100 dark green → Optimal
 *
 * The score number is drawn inside the gauge; set [score] = -1 to show "—".
 */
class ReadinessGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var score: Int = -1
        set(value) {
            field = value
            invalidate()
        }

    // ── Paints ────────────────────────────────────────────────────────────────

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // ── Geometry ──────────────────────────────────────────────────────────────

    private val oval = RectF()

    // Arc starts at 180° (left) and sweeps 180° to 360° (right).
    private fun scoreToAngleDeg(s: Float): Float = 180f + (s / 100f) * 180f
    private fun scoreToAngleRad(s: Float): Double =
        Math.toRadians(scoreToAngleDeg(s).toDouble())

    // Segment definitions: (fromScore, toScore, color)
    private val segments = listOf(
        Triple(0f,  50f, Color.parseColor("#E53935")),  // red
        Triple(50f, 70f, Color.parseColor("#F9A825")),  // amber
        Triple(70f, 85f, Color.parseColor("#43A047")),  // green
        Triple(85f, 100f, Color.parseColor("#2E7D32")), // dark green
    )

    // ── Measure ───────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // Height = ~62% of width: semicircle radius + room for number below pivot
        setMeasuredDimension(w, (w * 0.62f).toInt())
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f

        val radius = w * 0.40f
        val stroke = radius * 0.20f

        // Pivot sits far enough down that the arc top clears the view top
        val cy = h - radius * 0.10f

        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // ── Track background (faint) ──────────────────────────────────────────
        trackPaint.strokeWidth = stroke
        trackPaint.color = 0x22808080.toInt()
        canvas.drawArc(oval, 180f, 180f, false, trackPaint)

        // ── Coloured segments (with 1.5° gap between each) ────────────────────
        trackPaint.strokeWidth = stroke - 4f
        val gapDeg = 1.8f
        for ((from, to, color) in segments) {
            val startAngle = scoreToAngleDeg(from) + if (from == 0f) 0f else gapDeg / 2f
            val endAngle   = scoreToAngleDeg(to)   - if (to == 100f) 0f else gapDeg / 2f
            val sweep = endAngle - startAngle
            if (sweep <= 0f) continue
            trackPaint.color = color
            canvas.drawArc(oval, startAngle, sweep, false, trackPaint)
        }

        // ── Needle ────────────────────────────────────────────────────────────
        val drawScore = if (score < 0) 0f else score.toFloat()
        val angleRad  = scoreToAngleRad(drawScore)

        if (score >= 0) {
            val needleLen  = radius * 0.72f
            val needleBase = stroke * 0.18f   // half-width at pivot
            val tipX = cx + needleLen * cos(angleRad).toFloat()
            val tipY = cy + needleLen * sin(angleRad).toFloat()

            // Perpendicular vector for the needle base width
            val perpAngle = angleRad + Math.PI / 2.0
            val px = (needleBase * cos(perpAngle)).toFloat()
            val py = (needleBase * sin(perpAngle)).toFloat()

            val path = Path().apply {
                moveTo(tipX, tipY)
                lineTo(cx + px, cy + py)
                lineTo(cx - px, cy - py)
                close()
            }

            // Needle and pivot colours adapt to light/dark mode
            val isDark = (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val needleColor  = if (isDark) Color.WHITE      else Color.parseColor("#1A1A1A")
            val ringColor    = if (isDark) Color.WHITE      else Color.parseColor("#1A1A1A")
            val shadowColor  = if (isDark) 0x44000000.toInt() else 0x22000000.toInt()

            // Shadow
            needlePaint.color = shadowColor
            canvas.save()
            canvas.translate(2f, 3f)
            canvas.drawPath(path, needlePaint)
            canvas.restore()

            // Needle body
            needlePaint.color = needleColor
            canvas.drawPath(path, needlePaint)

            // Center dot (red pivot)
            val dotRadius = stroke * 0.30f
            dotPaint.color = Color.parseColor("#E53935")
            canvas.drawCircle(cx, cy, dotRadius, dotPaint)

            // Ring around pivot dot
            dotPaint.color = ringColor
            dotPaint.style = Paint.Style.STROKE
            (dotPaint as Paint).strokeWidth = 2.5f
            canvas.drawCircle(cx, cy, dotRadius, dotPaint)
            dotPaint.style = Paint.Style.FILL
        }

        // ── Score number ──────────────────────────────────────────────────────
        val scoreColor = when {
            score < 0  -> 0xFF888888.toInt()
            score >= 85 -> Color.parseColor("#2E7D32")
            score >= 70 -> Color.parseColor("#43A047")
            score >= 50 -> Color.parseColor("#F9A825")
            else        -> Color.parseColor("#E53935")
        }
        scorePaint.color = scoreColor
        scorePaint.textSize = radius * 0.52f
        val scoreText = if (score < 0) "—" else score.toString()
        // Draw above the pivot, centred in the arc
        val textY = cy - radius * 0.18f
        canvas.drawText(scoreText, cx, textY, scorePaint)
    }
}
