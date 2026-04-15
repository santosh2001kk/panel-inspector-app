package com.example.testerapigoogle

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object ReportGenerator {

    private const val PAGE_W = 595   // A4 width  in points
    private const val PAGE_H = 842   // A4 height in points
    private const val MARGIN = 40f
    private val GREEN  = Color.parseColor("#3DCD58")
    private val DARK   = Color.parseColor("#003865")
    private val RED    = Color.parseColor("#B71C1C")

    fun generate(
        context: Context,
        markedBitmap: Bitmap?,
        originalBitmap: Bitmap? = null,
        projectName: String,
        siteLocation: String,
        inspectorName: String,
        panelType: String,
        panelSummary: String,
        notes: String,
        warnings: List<String>,
        busbarOnly: Boolean = false,
        cubicle_line: String = "",
        task: String = "others"
    ): File {
        val doc  = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        val c    = page.canvas
        c.drawColor(Color.WHITE)
        var y    = 0f

        // ── Header bar ──────────────────────────────────────────────
        c.drawRect(0f, 0f, PAGE_W.toFloat(), 70f,
            Paint().apply { color = DARK; style = Paint.Style.FILL })
        val taskLabel = when (task.lowercase()) {
            "commissioning" -> "Commissioning Report"
            "maintenance"   -> "Maintenance Report"
            "modification"  -> "Modification Report"
            "replacement"   -> "Replacement Report"
            else            -> "Panel Inspection Report"
        }
        c.drawText(taskLabel, MARGIN, 44f,
            Paint().apply { color = Color.WHITE; textSize = 20f; isFakeBoldText = true; isAntiAlias = true })
        val dateStr = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()).format(Date())
        val datePaint = Paint().apply { color = Color.WHITE; textSize = 11f; isAntiAlias = true }
        c.drawText(dateStr, PAGE_W - MARGIN - datePaint.measureText(dateStr), 44f, datePaint)

        // Green accent line
        c.drawRect(0f, 70f, PAGE_W.toFloat(), 76f,
            Paint().apply { color = GREEN; style = Paint.Style.FILL })
        y = 90f

        // ── Project details row ──────────────────────────────────────
        val detailPaint = Paint().apply { color = Color.DKGRAY; textSize = 11f; isAntiAlias = true }
        val boldDetail  = Paint().apply { color = DARK; textSize = 11f; isFakeBoldText = true; isAntiAlias = true }
        if (projectName.isNotBlank()) {
            c.drawText("Project:", MARGIN, y + 12f, boldDetail)
            c.drawText(projectName, MARGIN + 55f, y + 12f, detailPaint)
            y += 16f
        }
        if (siteLocation.isNotBlank()) {
            c.drawText("Site:", MARGIN, y + 12f, boldDetail)
            c.drawText(siteLocation, MARGIN + 55f, y + 12f, detailPaint)
            y += 16f
        }
        if (inspectorName.isNotBlank()) {
            c.drawText("Inspector:", MARGIN, y + 12f, boldDetail)
            c.drawText(inspectorName, MARGIN + 55f, y + 12f, detailPaint)
            y += 16f
        }
        y += 10f

        // ── Marked zone image ────────────────────────────────────────
        if (markedBitmap != null) {
            val maxW = PAGE_W - 2 * MARGIN.toInt()
            val maxH = 220
            val rs   = minOf(maxW.toFloat() / markedBitmap.width, maxH.toFloat() / markedBitmap.height)
            val bmp  = if (rs < 1f)
                Bitmap.createScaledBitmap(markedBitmap,
                    (markedBitmap.width * rs).toInt(), (markedBitmap.height * rs).toInt(), true)
            else markedBitmap
            val imgX = (PAGE_W - bmp.width) / 2f
            c.drawBitmap(bmp, imgX, y, null)
            y += bmp.height + 16f
            if (bmp !== markedBitmap) bmp.recycle()
        }

        // ── Panel type card — skip for busbar-only mode ──────────────
        if (!busbarOnly) {
            // Pre-calculate how many lines panelSummary needs
            val summaryLines = if (panelSummary.isNotBlank())
                wrapTextToLines(panelSummary, Paint().apply { textSize = 12f; isAntiAlias = true },
                    PAGE_W - 2 * MARGIN - 24f)
            else emptyList()
            val cardHeight = 58f + summaryLines.size * 16f + 10f
            // Draw dark card background
            c.drawRoundRect(RectF(MARGIN, y, PAGE_W - MARGIN, y + cardHeight), 8f, 8f,
                Paint().apply { color = DARK; style = Paint.Style.FILL })
            c.drawText("PANEL TYPE", MARGIN + 12f, y + 18f,
                Paint().apply { color = Color.WHITE; textSize = 11f; isAntiAlias = true })
            c.drawText(panelType, MARGIN + 12f, y + 44f,
                Paint().apply { color = GREEN; textSize = 22f; isFakeBoldText = true; isAntiAlias = true })
            var summaryY = y + 60f
            val summaryPaint = Paint().apply { color = Color.WHITE; textSize = 12f; isAntiAlias = true }
            summaryLines.forEach { line ->
                c.drawText(line, MARGIN + 12f, summaryY, summaryPaint)
                summaryY += 16f
            }
            y += cardHeight + 12f
        }

        // ── Notes ────────────────────────────────────────────────────
        if (notes.isNotBlank()) {
            y = drawSection(c, y, "Notes", DARK)
            y = drawWrappedText(c, y, notes)
            y += 8f
        }

        // ── Cubicle / Busbar location info ───────────────────────────
        if (cubicle_line.isNotBlank()) {
            y = drawSection(c, y, "Cubicle & Busbar Info", GREEN)
            y = drawWrappedText(c, y, cubicle_line)
            y += 8f
        }

        // ── Safety warnings ──────────────────────────────────────────
        if (warnings.isNotEmpty()) {
            val footerLimit = PAGE_H - 50f
            // If not enough room for section header + at least 1 warning, skip to page 2
            if (y + 60f < footerLimit) {
                y = drawSection(c, y, "⚠ Safety Warnings", RED)
                for (w in warnings) {
                    if (y + 18f >= footerLimit) break
                    y = drawWrappedText(c, y, "• $w")
                }
            }
            y += 8f
        }

        // ── Footer ───────────────────────────────────────────────────
        val footerY = PAGE_H - 30f
        c.drawLine(MARGIN, footerY - 10f, PAGE_W - MARGIN, footerY - 10f,
            Paint().apply { color = Color.LTGRAY; strokeWidth = 1f })
        c.drawText("Generated by Panel Inspector  •  Schneider Electric", MARGIN, footerY + 8f,
            Paint().apply { color = Color.GRAY; textSize = 10f; isAntiAlias = true })

        doc.finishPage(page)

        // ── Page 2: Full-size detected image ─────────────────────────
        val page2Bitmap = markedBitmap ?: originalBitmap
        if (page2Bitmap != null) {
            val page2 = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 2).create())
            val c2    = page2.canvas
            c2.drawColor(Color.WHITE)

            // Header
            c2.drawRect(0f, 0f, PAGE_W.toFloat(), 70f,
                Paint().apply { color = DARK; style = Paint.Style.FILL })
            c2.drawText("Detected Panel Photo", MARGIN, 44f,
                Paint().apply { color = Color.WHITE; textSize = 20f; isFakeBoldText = true; isAntiAlias = true })
            c2.drawRect(0f, 70f, PAGE_W.toFloat(), 74f,
                Paint().apply { color = GREEN; style = Paint.Style.FILL })

            // Scale image to fill most of the page
            val maxW = (PAGE_W - 2 * MARGIN).toInt()
            val maxH = (PAGE_H - 110).toInt()
            val scale = minOf(maxW.toFloat() / page2Bitmap.width, maxH.toFloat() / page2Bitmap.height)
            val scaledW = (page2Bitmap.width * scale).toInt()
            val scaledH = (page2Bitmap.height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(page2Bitmap, scaledW, scaledH, true)
            val imgX = (PAGE_W - scaledW) / 2f
            val imgY = 90f
            c2.drawBitmap(scaled, imgX, imgY, null)
            if (scaled !== page2Bitmap) scaled.recycle()

            // Footer
            c2.drawLine(MARGIN, PAGE_H - 40f, PAGE_W - MARGIN, PAGE_H - 40f,
                Paint().apply { color = Color.LTGRAY; strokeWidth = 1f })
            c2.drawText("Generated by Panel Inspector  •  Schneider Electric", MARGIN, PAGE_H - 22f,
                Paint().apply { color = Color.GRAY; textSize = 10f; isAntiAlias = true })

            doc.finishPage(page2)
        }

        val dir  = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val name = "PanelReport_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf"
        val file = File(dir, name)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    // ── Combined report (multiple scans in one PDF) ───────────────────

    fun generateCombined(context: Context, records: List<ScanRecord>): File {
        val doc = PdfDocument()
        var pageNum = 1

        // ── Cover page ────────────────────────────────────────────────
        val cover = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum++).create())
        val cc    = cover.canvas
        cc.drawColor(Color.WHITE)   // fill transparent canvas so PDF viewers don't show black

        cc.drawRect(0f, 0f, PAGE_W.toFloat(), 70f,
            Paint().apply { color = DARK; style = Paint.Style.FILL })
        cc.drawText("Combined Inspection Report", MARGIN, 44f,
            Paint().apply { color = Color.WHITE; textSize = 20f; isFakeBoldText = true; isAntiAlias = true })
        val dateStr = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()).format(Date())
        val dp = Paint().apply { color = Color.WHITE; textSize = 11f; isAntiAlias = true }
        cc.drawText(dateStr, PAGE_W - MARGIN - dp.measureText(dateStr), 44f, dp)
        cc.drawRect(0f, 70f, PAGE_W.toFloat(), 76f,
            Paint().apply { color = GREEN; style = Paint.Style.FILL })

        var cy = 100f
        cc.drawText("Scans included in this report:", MARGIN, cy,
            Paint().apply { color = DARK; textSize = 13f; isFakeBoldText = true; isAntiAlias = true })
        cy += 24f

        val rowPaint  = Paint().apply { color = Color.DKGRAY; textSize = 11f; isAntiAlias = true }
        val boldPaint = Paint().apply { color = DARK; textSize = 11f; isFakeBoldText = true; isAntiAlias = true }
        records.forEachIndexed { idx, r ->
            val dateFmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(r.dateMs))
            cc.drawText("${idx + 1}.", MARGIN, cy, boldPaint)
            cc.drawText("$dateFmt  —  ${r.panelType.ifBlank { "Unknown Panel" }}", MARGIN + 22f, cy, rowPaint)
            cy += 16f
            if (r.siteLocation.isNotBlank()) {
                cc.drawText("Site: ${r.siteLocation}", MARGIN + 22f, cy, rowPaint)
                cy += 16f
            }
            cy += 4f
        }

        cc.drawLine(MARGIN, PAGE_H - 30f, PAGE_W - MARGIN, PAGE_H - 30f,
            Paint().apply { color = Color.LTGRAY; strokeWidth = 1f })
        cc.drawText("Generated by Panel Inspector  •  Schneider Electric", MARGIN, PAGE_H - 14f,
            Paint().apply { color = Color.GRAY; textSize = 10f; isAntiAlias = true })
        doc.finishPage(cover)

        // ── One page per scan ─────────────────────────────────────────
        for ((scanIdx, r) in records.withIndex()) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum++).create())
            val c    = page.canvas
            c.drawColor(Color.WHITE)   // white background
            var y    = 0f

            // Header
            c.drawRect(0f, 0f, PAGE_W.toFloat(), 70f,
                Paint().apply { color = DARK; style = Paint.Style.FILL })
            c.drawText("Scan ${scanIdx + 1} of ${records.size}", MARGIN, 30f,
                Paint().apply { color = Color.WHITE; textSize = 12f; isAntiAlias = true })
            c.drawText(r.panelType.ifBlank { "Panel Inspection" }, MARGIN, 52f,
                Paint().apply { color = GREEN; textSize = 18f; isFakeBoldText = true; isAntiAlias = true })
            c.drawRect(0f, 70f, PAGE_W.toFloat(), 76f,
                Paint().apply { color = GREEN; style = Paint.Style.FILL })
            y = 90f

            // Project details
            val bold = Paint().apply { color = DARK; textSize = 11f; isFakeBoldText = true; isAntiAlias = true }
            val detail = Paint().apply { color = Color.DKGRAY; textSize = 11f; isAntiAlias = true }
            val dateFmt2 = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()).format(Date(r.dateMs))
            c.drawText("Date:", MARGIN, y + 12f, bold); c.drawText(dateFmt2, MARGIN + 55f, y + 12f, detail); y += 16f
            if (r.projectName.isNotBlank())   { c.drawText("Project:",   MARGIN, y + 12f, bold); c.drawText(r.projectName,   MARGIN + 55f, y + 12f, detail); y += 16f }
            if (r.siteLocation.isNotBlank())  { c.drawText("Site:",      MARGIN, y + 12f, bold); c.drawText(r.siteLocation,  MARGIN + 55f, y + 12f, detail); y += 16f }
            if (r.inspectorName.isNotBlank()) { c.drawText("Inspector:", MARGIN, y + 12f, bold); c.drawText(r.inspectorName, MARGIN + 55f, y + 12f, detail); y += 16f }
            y += 10f

            // Image (if still exists on device)
            if (r.imagePath.isNotBlank()) {
                val bmpRaw = android.graphics.BitmapFactory.decodeFile(r.imagePath)
                if (bmpRaw != null) {
                    val maxW = PAGE_W - 2 * MARGIN.toInt()
                    val maxH = 200
                    val rs   = minOf(maxW.toFloat() / bmpRaw.width, maxH.toFloat() / bmpRaw.height)
                    val bmp  = if (rs < 1f) Bitmap.createScaledBitmap(bmpRaw, (bmpRaw.width*rs).toInt(), (bmpRaw.height*rs).toInt(), true) else bmpRaw
                    c.drawBitmap(bmp, (PAGE_W - bmp.width) / 2f, y, null)
                    y += bmp.height + 14f
                    if (bmp !== bmpRaw) bmp.recycle()
                    bmpRaw.recycle()
                }
            }

            // Notes
            if (r.notes.isNotBlank()) {
                y = drawSection(c, y, "Notes", DARK)
                y = drawWrappedText(c, y, r.notes)
                y += 8f
            }

            // Warnings
            if (r.warnings.isNotEmpty()) {
                y = drawSection(c, y, "⚠ Safety Warnings", RED)
                r.warnings.forEach { w -> y = drawWrappedText(c, y, "• $w") }
            }

            // Footer
            c.drawLine(MARGIN, PAGE_H - 30f, PAGE_W - MARGIN, PAGE_H - 30f,
                Paint().apply { color = Color.LTGRAY; strokeWidth = 1f })
            c.drawText("Generated by Panel Inspector  •  Schneider Electric", MARGIN, PAGE_H - 14f,
                Paint().apply { color = Color.GRAY; textSize = 10f; isAntiAlias = true })

            doc.finishPage(page)
        }

        val dir  = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val name = "CombinedReport_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf"
        val file = File(dir, name)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun drawCard(c: Canvas, y: Float, content: (Float) -> Float): Float {
        val endY  = content(y)
        c.drawRoundRect(RectF(MARGIN, y, PAGE_W - MARGIN, endY + 8f), 8f, 8f,
            Paint().apply { color = DARK; style = Paint.Style.FILL })
        content(y)  // draw text on top of background
        return endY + 8f
    }

    private fun drawSection(c: Canvas, y: Float, title: String, color: Int): Float {
        c.drawText(title, MARGIN, y + 14f,
            Paint().apply { this.color = color; textSize = 13f; isFakeBoldText = true; isAntiAlias = true })
        c.drawLine(MARGIN, y + 18f, PAGE_W - MARGIN, y + 18f,
            Paint().apply { this.color = color; strokeWidth = 2f })
        return y + 28f
    }

    private fun wrapTextToLines(text: String, paint: Paint, maxW: Float): List<String> {
        val result = mutableListOf<String>()
        // handle explicit newlines first
        for (paragraph in text.split("\n")) {
            var line = ""
            for (word in paragraph.split(" ")) {
                val test = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(test) > maxW && line.isNotEmpty()) {
                    result.add(line)
                    line = word
                } else {
                    line = test
                }
            }
            if (line.isNotEmpty()) result.add(line)
        }
        return result
    }

    private fun drawWrappedText(c: Canvas, startY: Float, text: String): Float {
        val paint  = Paint().apply { color = Color.DKGRAY; textSize = 12f; isAntiAlias = true }
        val maxW   = PAGE_W - 2 * MARGIN - 16f
        val lines  = wrapTextToLines(text, paint, maxW)
        var curY   = startY
        val footerLimit = PAGE_H - 50f   // don't draw past footer
        for (line in lines) {
            if (curY + 14f > footerLimit) break  // stop before overflow
            c.drawText(line, MARGIN + 8f, curY + 14f, paint)
            curY += 18f
        }
        return curY + 4f
    }
}
