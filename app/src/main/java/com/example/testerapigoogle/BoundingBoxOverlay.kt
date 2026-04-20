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

/**
 * BoundingBoxOverlay
 *
 * A transparent View that sits on top of the result ImageView (ivResult) and
 * draws all visual annotations over the panel photo:
 *
 *   1. Breaker bounding boxes — coloured rectangles around each detected breaker,
 *      with the product name above and circuit label/rating below.
 *
 *   2. Work zone — semi-transparent green rectangle showing the area the user
 *      drew as their intended work area.
 *
 *   3. Safety buffer — red dashed rectangle slightly larger than the work zone,
 *      used as the breaker detection boundary.
 *
 *   4. Cubicle segments — coloured boxes (C1, C2, C3...) drawn when the server
 *      returns cubicle-level segmentation (e.g. VBB location in PrismaSeT P).
 *
 *   5. Busbar strip — orange shaded zone on the left or right 18% of the panel
 *      shown when cubicle boxes are not available but busbar_side is known.
 *      Falls back to this simpler visualisation.
 *
 * Coordinate system:
 *   The server returns bounding boxes in original image pixel coordinates.
 *   The overlay scales everything using:
 *     sc   = min(viewWidth / imageWidth, viewHeight / imageHeight)
 *     offX = (viewWidth  - imageWidth  * sc) / 2   ← horizontal letterbox offset
 *     offY = (viewHeight - imageHeight * sc) / 2   ← vertical letterbox offset
 *   This matches how fitCenter scaling works on the ImageView underneath,
 *   so boxes line up perfectly with the photo.
 *
 * Colour coding for breakers:
 *   Red    (#F44336) — ACB family: MasterPact MTZ, MasterPact NT
 *   Orange (#FF9800) — MCCB family: Compact NSX, Compact NS
 *   Blue   (#2196F3) — MCB family: Acti9, iC60, Multi9
 *   Green  (#4CAF50) — unknown / other
 */
class BoundingBoxOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Data ──────────────────────────────────────────────────────────────────

    // All breakers returned by the server for this scan
    private val items       = mutableListOf<Detection>()

    // Original image dimensions — used to calculate the scale factor sc
    private var imgW        = 1
    private var imgH        = 1

    // Work zone and safety buffer coordinates (0-1000 normalised)
    private var workZone    : ZoneCoords? = null
    private var safetyBuf   : ZoneCoords? = null

    // "left" or "right" — which side the busbar strip is drawn on (fallback mode)
    private var busbarSide  : String? = null

    // VBB bounding box in original pixel coords [y1, x1, y2, x2]
    private var vbbBox      : FloatArray? = null

    // Total number of cubicles identified (used for labelling)
    private var cubicleCount: Int = 0

    // List of cubicle boxes in original pixel coords — each entry is [y1, x1, y2, x2]
    private val cubicleBoxes = mutableListOf<FloatArray>()

    // ── Paint objects ─────────────────────────────────────────────────────────
    // Defined once here to avoid creating new Paint objects on every draw call
    // (which would cause garbage collection pressure during animation/scrolling).

    // Used to draw the outline (stroke) of each breaker bounding box
    private val boxPaint  = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }

    // Used to draw the filled background behind label text
    private val bgPaint   = Paint().apply { style = Paint.Style.FILL }

    // White bold text for the product name label (e.g. "COMPACT NSX")
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 38f; isAntiAlias = true; isFakeBoldText = true }

    // Work zone green outline
    private val workZonePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#4CAF50")
        strokeWidth = 4f
        isAntiAlias = true
    }

    // Work zone semi-transparent green fill
    private val workZoneFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#224CAF50")   // 13% opacity green
    }

    // Safety buffer red dashed outline
    private val safetyBufferPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.RED
        strokeWidth = 4f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)  // 20px dash, 10px gap
    }

    // Small bold text for zone labels ("Work Zone", "Safety Buffer")
    private val zoneLabelPaint   = Paint().apply { color = Color.WHITE; textSize = 30f; isAntiAlias = true; isFakeBoldText = true }

    // Filled background behind zone label text
    private val zoneLabelBgPaint = Paint().apply { style = Paint.Style.FILL }

    // ── Public setters ────────────────────────────────────────────────────────
    // Called from ResultActivity after the server response is parsed.
    // Each setter stores the data and calls invalidate() to trigger a redraw.

    /** Set the list of detected breakers and the original image size. */
    fun setDetections(detections: List<Detection>, imageW: Int, imageH: Int) {
        items.clear()
        items.addAll(detections)
        imgW = imageW
        imgH = imageH
        invalidate()
    }

    /** Set the work zone (green) and safety buffer (red dashed) rectangles. */
    fun setZones(wz: ZoneCoords?, sb: ZoneCoords?) {
        workZone  = wz
        safetyBuf = sb
        invalidate()
    }

    /**
     * Set which side the busbar strip should be drawn on.
     * Only used as a fallback when cubicle boxes are not available.
     * "left" or "right" — draws an orange 18%-width strip on that side.
     */
    fun setBusbarSide(side: String?) {
        busbarSide = side
        invalidate()
    }

    /** Set the VBB bounding box in original pixel coords [y1, x1, y2, x2]. */
    fun setVbbBox(box: FloatArray?) {
        vbbBox = box
        invalidate()
    }

    /** Set the total cubicle count (used for reference, not drawn directly). */
    fun setCubicleCount(count: Int) {
        cubicleCount = count
        invalidate()
    }

    /**
     * getDetectionAt()
     *
     * Hit-tests a touch point against all drawn bounding boxes.
     * Used by ResultActivity to identify which breaker the user tapped.
     *
     * The same scale/offset calculation used in onDraw() is replicated here so
     * the touch coordinates map exactly to the same view-space rectangles that
     * are drawn on screen.
     *
     * @param touchX  Finger x position in view pixels (from MotionEvent.getX()).
     * @param touchY  Finger y position in view pixels (from MotionEvent.getY()).
     * @return The Detection whose bounding box contains the touch point,
     *         or null if no breaker was tapped.
     */
    fun getDetectionAt(touchX: Float, touchY: Float): Detection? {
        if (imgW <= 1 || imgH <= 1) return null
        val sc   = min(width.toFloat() / imgW, height.toFloat() / imgH)
        val offX = (width  - imgW * sc) / 2f
        val offY = (height - imgH * sc) / 2f
        // Iterate in reverse so the last-drawn (topmost) box is checked first
        for (d in items.reversed()) {
            val x1 = d.x1 * sc + offX
            val y1 = d.y1 * sc + offY
            val x2 = d.x2 * sc + offX
            val y2 = d.y2 * sc + offY
            if (touchX in x1..x2 && touchY in y1..y2) return d
        }
        return null
    }

    /**
     * Set all cubicle bounding boxes at once.
     * @param flat  Flat array of pixel coords packed as [y1,x1,y2,x2, y1,x1,y2,x2, ...]
     *              Every 4 values is one cubicle box.
     */
    fun setCubicleBoxes(flat: FloatArray) {
        cubicleBoxes.clear()
        var i = 0
        while (i + 3 < flat.size) {
            cubicleBoxes.add(floatArrayOf(flat[i], flat[i+1], flat[i+2], flat[i+3]))
            i += 4
        }
        invalidate()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Calculate the scale factor and letterbox offsets so overlays align
        // with the fitCenter-scaled image in the ImageView underneath.
        val sc   = min(width.toFloat() / imgW, height.toFloat() / imgH)
        val offX = (width  - imgW * sc) / 2f
        val offY = (height - imgH * sc) / 2f

        // ── 1. Safety buffer (red dashed) — drawn first so it's behind everything ──
        safetyBuf?.let { sb ->
            val rx1 = sb.xmin / 1000f * imgW * sc + offX
            val ry1 = sb.ymin / 1000f * imgH * sc + offY
            val rx2 = sb.xmax / 1000f * imgW * sc + offX
            val ry2 = sb.ymax / 1000f * imgH * sc + offY
            canvas.drawRect(RectF(rx1, ry1, rx2, ry2), safetyBufferPaint)
            // Label shown below the bottom-left corner
            drawZoneLabel(canvas, "Safety Buffer", rx1, ry2 + 4f, Color.RED, below = true)
        }

        // ── 2. Work zone (green) ─────────────────────────────────────────────
        workZone?.let { wz ->
            val wx1 = wz.xmin / 1000f * imgW * sc + offX
            val wy1 = wz.ymin / 1000f * imgH * sc + offY
            val wx2 = wz.xmax / 1000f * imgW * sc + offX
            val wy2 = wz.ymax / 1000f * imgH * sc + offY
            canvas.drawRect(RectF(wx1, wy1, wx2, wy2), workZoneFillPaint)  // semi-transparent fill
            canvas.drawRect(RectF(wx1, wy1, wx2, wy2), workZonePaint)      // solid green border
            drawZoneLabel(canvas, "Work Zone", wx1, wy1, Color.parseColor("#4CAF50"), below = true)
        }

        // ── 3. Cubicle segments (C1, C2, C3...) ──────────────────────────────
        // Each cubicle gets a distinct colour and a "C1"/"C2" label in the centre.
        // These are drawn when the server returns cubicle-level segmentation
        // (e.g. in busbar-only mode or Analyze Zone mode for PrismaSeT P / Okken).
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
                // box is in original pixel coords [y1, x1, y2, x2]
                val left   = box[1] * sc + offX
                val top    = box[0] * sc + offY
                val right  = box[3] * sc + offX
                val bottom = box[2] * sc + offY
                val rect   = RectF(left, top, right, bottom)

                val color = cubicleColors[idx % cubicleColors.size]
                cStroke.color = color
                cFill.color   = (color and 0x00FFFFFF) or 0x33000000  // 20% opacity fill

                canvas.drawRect(rect, cFill)
                canvas.drawRect(rect, cStroke)

                // Draw "C1", "C2" etc. centred inside the cubicle box
                val label = "C${idx + 1}"
                val tw    = cText.measureText(label)
                val lx    = rect.centerX() - tw / 2f
                val ly    = rect.centerY() + 14f
                cTextBg.color = (color and 0x00FFFFFF) or 0xCC000000.toInt()  // 80% opacity bg
                canvas.drawRect(RectF(lx - 8f, ly - 40f, lx + tw + 8f, ly + 10f), cTextBg)
                canvas.drawText(label, lx, ly, cText)
            }

        } else {
            // ── 3b. Fallback: busbar strip ────────────────────────────────────
            // When cubicle segmentation was not run, but the server told us which
            // side the busbar compartment is on, draw a simple orange 18% strip.
            busbarSide?.let { side ->
                val imgLeft   = offX
                val imgTop    = offY
                val imgRight  = offX + imgW * sc
                val imgBottom = offY + imgH * sc
                val stripW    = imgW * sc * 0.18f   // 18% of panel width = VBB section

                val rect = when (side) {
                    "left"  -> RectF(imgLeft,           imgTop, imgLeft + stripW,  imgBottom)
                    "right" -> RectF(imgRight - stripW, imgTop, imgRight,           imgBottom)
                    else    -> null
                } ?: return@let

                val fill   = Paint().apply { style = Paint.Style.FILL;   color = Color.parseColor("#99FF6B00") }
                val stroke = Paint().apply { style = Paint.Style.STROKE; color = Color.parseColor("#FF6B00"); strokeWidth = 5f; isAntiAlias = true }
                val text   = Paint().apply { color = Color.WHITE; textSize = 32f; isAntiAlias = true; isFakeBoldText = true }
                val textBg = Paint().apply { style = Paint.Style.FILL; color = Color.parseColor("#CC000000") }

                canvas.drawRect(rect, fill)
                canvas.drawRect(rect, stroke)

                val lbl = "⚠ Busbar Zone"
                val tw  = text.measureText(lbl)
                val lx  = rect.centerX() - tw / 2f
                val ly  = rect.centerY()
                canvas.drawRect(RectF(lx - 6f, ly - 36f, lx + tw + 6f, ly + 8f), textBg)
                canvas.drawText(lbl, lx, ly, text)
            }
        }

        // ── 4. Breaker bounding boxes ─────────────────────────────────────────
        // For each detected breaker, draw:
        //   a) Coloured rectangle around the breaker body
        //   b) Product name label above the box (e.g. "COMPACT NSX")
        //   c) Circuit label + rating tag below the box (e.g. "LV MAIN  |  400A")
        //      — only shown if Gemini was able to read them from the photo
        for (d in items) {
            // Convert pixel coords to view coords using scale + offset
            val x1 = d.x1 * sc + offX
            val y1 = d.y1 * sc + offY
            val x2 = d.x2 * sc + offX
            val y2 = d.y2 * sc + offY

            // Pick box colour based on breaker family
            val itemColor = when {
                d.label.contains("MasterPact", ignoreCase = true) -> Color.parseColor("#F44336") // red — ACB
                d.label.contains("Compact", ignoreCase = true)    -> Color.parseColor("#FF9800") // orange — MCCB
                d.label.contains("Acti9",  ignoreCase = true) ||
                d.label.contains("iC60",   ignoreCase = true) ||
                d.label.contains("Multi9", ignoreCase = true)     -> Color.parseColor("#2196F3") // blue — MCB
                else -> Color.parseColor("#4CAF50")                                              // green — unknown
            }
            boxPaint.color = itemColor
            bgPaint.color  = itemColor
            canvas.drawRect(RectF(x1, y1, x2, y2), boxPaint)

            // ── Product name label ────────────────────────────────────────────
            // Preferred position: just above the top edge of the box.
            // Fallback: inside the top of the box if there's no space above.
            val label  = d.label
            val tw     = textPaint.measureText(label)
            val labelH = 40f
            val labelY = if (y1 >= labelH) y1 - labelH else y1
            canvas.drawRect(RectF(x1, labelY, x1 + tw + 12f, labelY + labelH), bgPaint)
            canvas.drawText(label, x1 + 6f, labelY + 28f, textPaint)

            // ── Circuit label + rating tag ────────────────────────────────────
            // Shown below the bottom edge of the box.
            // Combines both into one line: "LV MAIN  |  400A"
            // If only one is available, shows just that (e.g. "400A" or "LV MAIN").
            // If neither is readable, this block is skipped entirely.
            val subLine = listOf(d.circuitLabel, d.rating)
                .filter { it.isNotBlank() }
                .joinToString("  |  ")

            if (subLine.isNotBlank()) {
                val subPaint = Paint().apply {
                    color          = Color.WHITE
                    textSize       = 30f
                    isAntiAlias    = true
                    isFakeBoldText = false
                }
                // Background uses the same breaker colour at 80% opacity for readability
                val subBg = Paint().apply {
                    style = Paint.Style.FILL
                    color = (itemColor and 0x00FFFFFF) or 0xCC000000.toInt()
                }
                val stw = subPaint.measureText(subLine)
                val sy  = y2 + 4f   // 4px gap below the bounding box
                canvas.drawRect(RectF(x1, sy, x1 + stw + 12f, sy + 36f), subBg)
                canvas.drawText(subLine, x1 + 6f, sy + 26f, subPaint)
            }
        }
    }

    /**
     * drawZoneLabel()
     *
     * Draws a small coloured pill label for the work zone or safety buffer.
     *
     * @param canvas  Canvas to draw on.
     * @param text    Label text (e.g. "Work Zone", "Safety Buffer").
     * @param x       Left edge x coordinate in view pixels.
     * @param y       Top or anchor y coordinate in view pixels.
     * @param color   Background colour of the pill.
     * @param below   If true, the pill is drawn below y; if false, above y.
     */
    private fun drawZoneLabel(canvas: Canvas, text: String, x: Float, y: Float, color: Int, below: Boolean = false) {
        val tw    = zoneLabelPaint.measureText(text)
        val rectY = if (below) y else (if (y > 36f) y - 36f else y)
        zoneLabelBgPaint.color = color
        canvas.drawRect(RectF(x, rectY, x + tw + 12f, rectY + 36f), zoneLabelBgPaint)
        canvas.drawText(text, x + 6f, rectY + 26f, zoneLabelPaint)
    }
}
