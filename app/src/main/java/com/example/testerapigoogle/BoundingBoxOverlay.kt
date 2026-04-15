package com.example.testerapigoogle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class BoundingBoxOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val items      = mutableListOf<Detection>()
    private var imgW       = 1
    private var imgH       = 1
    private var workZone   : ZoneCoords? = null
    private var safetyBuf  : ZoneCoords? = null
    private var busbarSide  : String? = null
    private var vbbBox      : FloatArray? = null
    private var cubicleCount: Int = 0
    // Each entry: [y1, x1, y2, x2] pixel coords — one per detected cubicle
    private val cubicleBoxes = mutableListOf<FloatArray>()

    private val boxPaint  = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }
    private val bgPaint   = Paint().apply { style = Paint.Style.FILL }
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 38f; isAntiAlias = true; isFakeBoldText = true }

    private val workZonePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#4CAF50")
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val workZoneFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#224CAF50")
    }
    private val safetyBufferPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.RED
        strokeWidth = 4f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }
    private val zoneLabelPaint = Paint().apply {
        color = Color.WHITE; textSize = 30f; isAntiAlias = true; isFakeBoldText = true
    }
    private val zoneLabelBgPaint = Paint().apply { style = Paint.Style.FILL }

    fun setDetections(detections: List<Detection>, imageW: Int, imageH: Int) {
        items.clear()
        items.addAll(detections)
        imgW = imageW
        imgH = imageH
        invalidate()
    }

    fun setZones(wz: ZoneCoords?, sb: ZoneCoords?) {
        workZone  = wz
        safetyBuf = sb
        invalidate()
    }

    fun setBusbarSide(side: String?) {
        busbarSide = side
        invalidate()
    }

    fun setVbbBox(box: FloatArray?) {
        vbbBox = box
        invalidate()
    }

    fun setCubicleCount(count: Int) {
        cubicleCount = count
        invalidate()
    }

    /** Pass flat array [y1,x1,y2,x2, y1,x1,y2,x2, ...] — one group of 4 per cubicle */
    fun setCubicleBoxes(flat: FloatArray) {
        cubicleBoxes.clear()
        var i = 0
        while (i + 3 < flat.size) {
            cubicleBoxes.add(floatArrayOf(flat[i], flat[i+1], flat[i+2], flat[i+3]))
            i += 4
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val sc   = min(width.toFloat() / imgW, height.toFloat() / imgH)
        val offX = (width  - imgW * sc) / 2f
        val offY = (height - imgH * sc) / 2f

        // Draw safety buffer (red dashed) — label at bottom-left
        safetyBuf?.let { sb ->
            val rx1 = sb.xmin / 1000f * imgW * sc + offX
            val ry1 = sb.ymin / 1000f * imgH * sc + offY
            val rx2 = sb.xmax / 1000f * imgW * sc + offX
            val ry2 = sb.ymax / 1000f * imgH * sc + offY
            canvas.drawRect(RectF(rx1, ry1, rx2, ry2), safetyBufferPaint)
            drawZoneLabel(canvas, "Safety Buffer", rx1, ry2 + 4f, Color.RED, below = true)
        }

        // Draw work zone (green) — label at top-left inside box
        workZone?.let { wz ->
            val wx1 = wz.xmin / 1000f * imgW * sc + offX
            val wy1 = wz.ymin / 1000f * imgH * sc + offY
            val wx2 = wz.xmax / 1000f * imgW * sc + offX
            val wy2 = wz.ymax / 1000f * imgH * sc + offY
            canvas.drawRect(RectF(wx1, wy1, wx2, wy2), workZoneFillPaint)
            canvas.drawRect(RectF(wx1, wy1, wx2, wy2), workZonePaint)
            drawZoneLabel(canvas, "Work Zone", wx1, wy1, Color.parseColor("#4CAF50"), below = true)
        }

        // Draw each detected cubicle segment as a distinct coloured box
        if (cubicleBoxes.isNotEmpty()) {
            val cubicleColors = listOf(
                Color.parseColor("#E53935"), // red
                Color.parseColor("#1E88E5"), // blue
                Color.parseColor("#43A047"), // green
                Color.parseColor("#FB8C00"), // orange
                Color.parseColor("#8E24AA"), // purple
                Color.parseColor("#00ACC1"), // cyan
            )
            val cStroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }
            val cFill   = Paint().apply { style = Paint.Style.FILL }
            val cText   = Paint().apply { color = Color.WHITE; textSize = 36f; isAntiAlias = true; isFakeBoldText = true }
            val cTextBg = Paint().apply { style = Paint.Style.FILL }

            cubicleBoxes.forEachIndexed { idx, box ->
                // box = [y1, x1, y2, x2] in original pixel coords
                val left   = box[1] * sc + offX
                val top    = box[0] * sc + offY
                val right  = box[3] * sc + offX
                val bottom = box[2] * sc + offY
                val rect   = RectF(left, top, right, bottom)

                val color = cubicleColors[idx % cubicleColors.size]
                cStroke.color = color
                cFill.color   = (color and 0x00FFFFFF) or 0x33000000  // 20% fill

                canvas.drawRect(rect, cFill)
                canvas.drawRect(rect, cStroke)

                // Label "C1", "C2" etc. centred inside the box
                val label = "C${idx + 1}"
                val tw    = cText.measureText(label)
                val lx    = rect.centerX() - tw / 2f
                val ly    = rect.centerY() + 14f
                cTextBg.color = (color and 0x00FFFFFF) or 0xCC000000.toInt()
                canvas.drawRect(RectF(lx - 8f, ly - 40f, lx + tw + 8f, ly + 10f), cTextBg)
                canvas.drawText(label, lx, ly, cText)
            }
        } else {
            // Fallback: draw 18% busbar strip if busbar_side is known
            busbarSide?.let { side ->
                val imgLeft   = offX;             val imgTop    = offY
                val imgRight  = offX + imgW * sc; val imgBottom = offY + imgH * sc
                val stripW    = imgW * sc * 0.18f
                val rect = when (side) {
                    "left"  -> RectF(imgLeft,          imgTop, imgLeft + stripW, imgBottom)
                    "right" -> RectF(imgRight - stripW, imgTop, imgRight,         imgBottom)
                    else    -> null
                } ?: return@let
                val fill   = Paint().apply { style = Paint.Style.FILL;   color = Color.parseColor("#99FF6B00") }
                val stroke = Paint().apply { style = Paint.Style.STROKE; color = Color.parseColor("#FF6B00"); strokeWidth = 5f; isAntiAlias = true }
                val text   = Paint().apply { color = Color.WHITE; textSize = 32f; isAntiAlias = true; isFakeBoldText = true }
                val textBg = Paint().apply { style = Paint.Style.FILL; color = Color.parseColor("#CC000000") }
                canvas.drawRect(rect, fill)
                canvas.drawRect(rect, stroke)
                val lbl = "⚠ Busbar Zone"
                val tw = text.measureText(lbl)
                val lx = rect.centerX() - tw / 2f
                val ly = rect.centerY()
                canvas.drawRect(RectF(lx - 6f, ly - 36f, lx + tw + 6f, ly + 8f), textBg)
                canvas.drawText(lbl, lx, ly, text)
            }
        }

        // Draw detections — label inside box at top
        for (d in items) {
            val x1 = d.x1 * sc + offX
            val y1 = d.y1 * sc + offY
            val x2 = d.x2 * sc + offX
            val y2 = d.y2 * sc + offY

            val color = when {
                d.label.contains("MasterPact", ignoreCase = true) -> Color.parseColor("#F44336") // red — ACB family
                d.label.contains("Compact", ignoreCase = true)    -> Color.parseColor("#FF9800") // orange — MCCB family
                d.label.contains("Acti9", ignoreCase = true) ||
                d.label.contains("iC60", ignoreCase = true)  ||
                d.label.contains("Multi9", ignoreCase = true)     -> Color.parseColor("#2196F3") // blue — MCB family
                else -> Color.parseColor("#4CAF50")
            }
            boxPaint.color = color
            bgPaint.color  = color
            canvas.drawRect(RectF(x1, y1, x2, y2), boxPaint)

            // Label: prefer above the box, fall back to inside top if not enough space
            val label  = d.label
            val tw     = textPaint.measureText(label)
            val labelH = 40f
            val labelY = if (y1 >= labelH) y1 - labelH else y1
            canvas.drawRect(RectF(x1, labelY, x1 + tw + 12f, labelY + labelH), bgPaint)
            canvas.drawText(label, x1 + 6f, labelY + 28f, textPaint)
        }
    }

    private fun drawZoneLabel(canvas: Canvas, text: String, x: Float, y: Float, color: Int, below: Boolean = false) {
        val tw    = zoneLabelPaint.measureText(text)
        val rectY = if (below) y else (if (y > 36f) y - 36f else y)
        zoneLabelBgPaint.color = color
        canvas.drawRect(RectF(x, rectY, x + tw + 12f, rectY + 36f), zoneLabelBgPaint)
        canvas.drawText(text, x + 6f, rectY + 26f, zoneLabelPaint)
    }
}
