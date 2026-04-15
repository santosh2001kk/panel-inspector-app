package com.example.testerapigoogle

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class VbbOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var vbbBox: RectF? = null       // pixel coords relative to this view
    private var vbbSide: String = ""
    private var imgW: Int = 0
    private var imgH: Int = 0

    private val boxPaint = Paint().apply {
        color  = Color.parseColor("#FF3DCD58")
        style  = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val fillPaint = Paint().apply {
        color = Color.parseColor("#443DCD58")
        style = Paint.Style.FILL
    }
    private val labelBgPaint = Paint().apply {
        color = Color.parseColor("#CC3DCD58")
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint().apply {
        color     = Color.WHITE
        textSize  = 36f
        typeface  = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    /** Set the VBB bounding box in pixel coords of the ORIGINAL image. */
    fun setVbbBox(box: IntArray, imageW: Int, imageH: Int, side: String) {
        imgW    = imageW
        imgH    = imageH
        vbbSide = side.uppercase()
        vbbBox  = RectF(
            box[1].toFloat(), box[0].toFloat(),
            box[3].toFloat(), box[2].toFloat()
        ) // xmin, ymin, xmax, ymax in image pixels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val box = vbbBox ?: return
        if (imgW == 0 || imgH == 0) return

        // Scale image coords → view coords (fitCenter scaling)
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val scaleX = viewW / imgW
        val scaleY = viewH / imgH
        val scale  = minOf(scaleX, scaleY)
        val offsetX = (viewW - imgW * scale) / 2f
        val offsetY = (viewH - imgH * scale) / 2f

        val rect = RectF(
            box.left   * scale + offsetX,
            box.top    * scale + offsetY,
            box.right  * scale + offsetX,
            box.bottom * scale + offsetY
        )

        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(rect, boxPaint)

        // Label background + text
        val label   = "VBB — $vbbSide"
        val padding = 8f
        val textW   = labelPaint.measureText(label)
        val textH   = labelPaint.textSize
        val labelRect = RectF(rect.left, rect.top - textH - padding * 2, rect.left + textW + padding * 2, rect.top)
        canvas.drawRoundRect(labelRect, 6f, 6f, labelBgPaint)
        canvas.drawText(label, rect.left + padding, rect.top - padding, labelPaint)
    }
}
