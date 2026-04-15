package com.example.testerapigoogle

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class WorkZoneOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var imgW = 1
    private var imgH = 1

    private var startX = 0f
    private var startY = 0f
    private var endX   = 0f
    private var endY   = 0f
    private var hasZone = false

    var onZoneChanged: ((workZone: ZoneCoords, safetyBuffer: ZoneCoords) -> Unit)? = null

    private val workZonePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#4CAF50")
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val workZoneFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#334CAF50")
    }
    private val safetyPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.RED
        strokeWidth = 4f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        isAntiAlias = true
        isFakeBoldText = true
    }
    private val labelBgPaint = Paint().apply { style = Paint.Style.FILL }

    fun setImageSize(w: Int, h: Int) { imgW = w; imgH = h }

    fun getZones(): Pair<ZoneCoords, ZoneCoords>? {
        if (!hasZone) return null
        return computeZones()
    }

    private fun sc()   = min(width.toFloat() / imgW, height.toFloat() / imgH)
    private fun offX() = (width  - imgW * sc()) / 2f
    private fun offY() = (height - imgH * sc()) / 2f

    private fun toNorm(vx: Float, vy: Float): Pair<Float, Float> {
        val s = sc(); val ox = offX(); val oy = offY()
        return Pair(
            ((vx - ox) / s / imgW * 1000f).coerceIn(0f, 1000f),
            ((vy - oy) / s / imgH * 1000f).coerceIn(0f, 1000f)
        )
    }

    private fun toView(nx: Float, ny: Float): Pair<Float, Float> {
        val s = sc(); val ox = offX(); val oy = offY()
        return Pair(nx / 1000f * imgW * s + ox, ny / 1000f * imgH * s + oy)
    }

    private fun computeZones(): Pair<ZoneCoords, ZoneCoords> {
        val (sx, sy) = toNorm(startX, startY)
        val (ex, ey) = toNorm(endX,   endY)
        val wzXmin = minOf(sx, ex).toInt()
        val wzYmin = minOf(sy, ey).toInt()
        val wzXmax = maxOf(sx, ex).toInt()
        val wzYmax = maxOf(sy, ey).toInt()
        val workZone = ZoneCoords(wzYmin, wzXmin, wzYmax, wzXmax)

        val expand = 80 // 8% of 1000
        val safetyBuffer = ZoneCoords(
            ymin = (wzYmin - expand).coerceAtLeast(0),
            xmin = (wzXmin - expand).coerceAtLeast(0),
            ymax = (wzYmax + expand).coerceAtMost(1000),
            xmax = (wzXmax + expand).coerceAtMost(1000)
        )
        return Pair(workZone, safetyBuffer)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                endX   = event.x; endY   = event.y
                hasZone = false
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x; endY = event.y
                hasZone = true
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                endX = event.x; endY = event.y
                hasZone = true
                invalidate()
                val (wz, sb) = computeZones()
                onZoneChanged?.invoke(wz, sb)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasZone) return

        val (wz, sb) = computeZones()

        // Safety buffer (red dashed)
        val (sbx1, sby1) = toView(sb.xmin.toFloat(), sb.ymin.toFloat())
        val (sbx2, sby2) = toView(sb.xmax.toFloat(), sb.ymax.toFloat())
        canvas.drawRect(RectF(sbx1, sby1, sbx2, sby2), safetyPaint)

        // Work zone (green fill + stroke)
        val (wzx1, wzy1) = toView(wz.xmin.toFloat(), wz.ymin.toFloat())
        val (wzx2, wzy2) = toView(wz.xmax.toFloat(), wz.ymax.toFloat())
        canvas.drawRect(RectF(wzx1, wzy1, wzx2, wzy2), workZoneFillPaint)
        canvas.drawRect(RectF(wzx1, wzy1, wzx2, wzy2), workZonePaint)

        drawLabel(canvas, "Work Zone",     wzx1, wzy1, Color.parseColor("#4CAF50"))
        drawLabel(canvas, "Safety Buffer", sbx1, sby1, Color.RED)
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float, color: Int) {
        val tw  = labelPaint.measureText(text)
        val top = if (y > 44f) y - 44f else y
        labelBgPaint.color = color
        canvas.drawRect(RectF(x, top, x + tw + 12f, top + 40f), labelBgPaint)
        canvas.drawText(text, x + 6f, top + 28f, labelPaint)
    }
}
