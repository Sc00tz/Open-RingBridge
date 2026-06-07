package dev.ringbridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * A small, axis-less filled line chart for metric-card previews on the Home grid.
 *
 * Set [values] to a short series (oldest → newest) and [accentColor] to the metric's
 * accent. The line is drawn in the accent colour with a soft vertical gradient fill
 * beneath it. With fewer than two points the view draws nothing, so callers can hide
 * the view entirely for no-data cards.
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var accentColor: Int = Color.parseColor("#FF4488DD")
        set(value) { field = value; invalidate() }

    var values: List<Float> = emptyList()
        set(value) { field = value; invalidate() }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val linePath = Path()
    private val fillPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pts = values
        if (pts.size < 2) return

        val w = width.toFloat()
        val h = height.toFloat()
        val pad = linePaint.strokeWidth.coerceAtLeast(4f)
        val usableH = h - pad * 2f

        val min = pts.min()
        val max = pts.max()
        val range = (max - min).takeIf { it > 0f } ?: 1f

        fun x(i: Int) = if (pts.size == 1) w / 2f else w * i / (pts.size - 1)
        fun y(v: Float) = pad + usableH * (1f - (v - min) / range)

        linePath.reset()
        fillPath.reset()
        linePath.moveTo(x(0), y(pts[0]))
        fillPath.moveTo(x(0), h)
        fillPath.lineTo(x(0), y(pts[0]))
        for (i in 1 until pts.size) {
            linePath.lineTo(x(i), y(pts[i]))
            fillPath.lineTo(x(i), y(pts[i]))
        }
        fillPath.lineTo(x(pts.size - 1), h)
        fillPath.close()

        // Gradient fill: accent (≈25% alpha) fading to transparent toward the bottom.
        val fillTop = (0x40 shl 24) or (accentColor and 0x00FFFFFF)
        val fillBot = accentColor and 0x00FFFFFF
        fillPaint.shader = LinearGradient(0f, 0f, 0f, h, fillTop, fillBot, Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, fillPaint)

        linePaint.color = accentColor
        canvas.drawPath(linePath, linePaint)
    }

    init {
        // Default line width scales with density.
        linePaint.strokeWidth = resources.displayMetrics.density * 1.8f
    }
}
