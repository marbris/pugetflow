package com.pugetflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Minimal line chart: value (Y) vs along-river distance in km (X), with a title,
 * axis min/mid/max labels, a connecting line and dots. Points with a null value
 * create a gap. Theme-neutral colours so it reads in light or dark.
 */
class GraphView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private var pts: List<Pair<Double, Double>> = emptyList()
    private var title: String = ""
    private var unit: String = ""
    private var lineColor: Int = Color.rgb(2, 136, 209)

    private val axisColor = Color.rgb(150, 150, 150)
    private val gridColor = Color.argb(40, 150, 150, 150)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = axisColor; strokeWidth = dp(1f) }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gridColor; strokeWidth = dp(1f) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = axisColor; textSize = sp(11f) }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = axisColor; textSize = sp(13f); isFakeBoldText = true }

    fun setData(points: List<Pair<Double, Double?>>, title: String, unit: String, lineColor: Int) {
        this.pts = points.mapNotNull { (x, y) -> if (y != null) x to y else null }.sortedBy { it.first }
        this.title = title
        this.unit = unit
        this.lineColor = lineColor
        linePaint.color = lineColor
        linePaint.strokeWidth = dp(2f)
        dotPaint.color = lineColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val left = dp(44f); val right = w - dp(10f)
        val top = dp(22f); val bottom = h - dp(24f)

        canvas.drawText(title, left, dp(15f), titlePaint)

        if (pts.isEmpty()) {
            canvas.drawText("No data available", left, (top + bottom) / 2, textPaint)
            return
        }

        var minX = pts.first().first; var maxX = pts.last().first
        var minY = pts.minOf { it.second }; var maxY = pts.maxOf { it.second }
        if (maxX - minX < 1e-6) maxX = minX + 1
        if (maxY - minY < 1e-6) { maxY += 1; minY -= 1 }
        val padY = (maxY - minY) * 0.10
        minY -= padY; maxY += padY

        fun px(x: Double) = (left + (x - minX) / (maxX - minX) * (right - left)).toFloat()
        fun py(y: Double) = (bottom - (y - minY) / (maxY - minY) * (bottom - top)).toFloat()

        // Axes
        canvas.drawLine(left, top, left, bottom, axisPaint)
        canvas.drawLine(left, bottom, right, bottom, axisPaint)

        // Y grid + labels (min, mid, max)
        for (f in listOf(0.0, 0.5, 1.0)) {
            val yv = minY + (maxY - minY) * f
            val y = py(yv)
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText(fmt(yv), dp(2f), y + sp(4f), textPaint)
        }

        // X labels (start, end)
        canvas.drawText("${fmt(minX)}", left, bottom + sp(16f), textPaint)
        val endLbl = "${fmt(maxX)} km"
        canvas.drawText(endLbl, right - textPaint.measureText(endLbl), bottom + sp(16f), textPaint)

        // Line + dots
        val path = Path()
        pts.forEachIndexed { i, (x, y) ->
            val cx = px(x); val cy = py(y)
            if (i == 0) path.moveTo(cx, cy) else path.lineTo(cx, cy)
        }
        canvas.drawPath(path, linePaint)
        for ((x, y) in pts) canvas.drawCircle(px(x), py(y), dp(3f), dotPaint)
    }

    private fun fmt(v: Double): String =
        if (kotlin.math.abs(v) >= 100 || v == Math.floor(v)) v.toLong().toString()
        else String.format("%.1f", v)

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
