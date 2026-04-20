package com.example.testerapigoogle

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import androidx.exifinterface.media.ExifInterface
import com.bumptech.glide.Glide
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ResultActivity : AppCompatActivity() {

    // Captured after overlay draws — used for the report image
    private var markedBitmap: Bitmap? = null

    // Tap-to-read: shared detector instance used for readLabel() calls
    private val detector = GoogleStudioDetector()

    // The current list of detections — kept mutable so tap-to-read can update
    // a Detection's circuitLabel/rating and refresh the overlay in place.
    private val currentDetections: MutableList<Detection> = mutableListOf()

    // Original image dimensions — needed to re-call setDetections() after an update
    private var imageWidth  = 0
    private var imageHeight = 0

    // Absolute path of the captured panel photo — used to crop a breaker region
    private var currentImagePath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        // Push the panel card below the status bar so it doesn't overlap system icons
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.cardPanel)) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val extraDp = (10 * view.resources.displayMetrics.density).toInt()
            (view.layoutParams as CoordinatorLayout.LayoutParams).topMargin = statusBarHeight + extraDp
            view.requestLayout()
            insets
        }

        // Set up bottom sheet
        val bottomSheet = findViewById<LinearLayout>(R.id.bottomSheet)
        val sheetBehavior = BottomSheetBehavior.from(bottomSheet)
        sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        val imagePath      = intent.getStringExtra("image_path")
        currentImagePath   = imagePath   // save for tap-to-read crop
        val detectionsJson = intent.getStringExtra("detections_json")
        val notes          = intent.getStringExtra("notes") ?: ""
        val warnings       = intent.getStringArrayListExtra("safety_warnings") ?: arrayListOf()
        val workZoneJson   = intent.getStringExtra("work_zone")
        val safetyBufJson  = intent.getStringExtra("safety_buffer")
        val panelType      = intent.getStringExtra("panel_type") ?: ""
        val panelSummary   = intent.getStringExtra("panel_summary") ?: ""
        val qrCodes        = intent.getStringArrayListExtra("qr_codes") ?: arrayListOf()
        val busbarSide     = intent.getStringExtra("busbar_side") ?: "unknown"
        val vbbBox         = intent.getFloatArrayExtra("vbb_box")   // [y1,x1,y2,x2] pixels
        val cubicleCount   = intent.getIntExtra("cubicle_count", 0)
        val vbbPosition    = intent.getIntExtra("vbb_position", 0)
        val busbarOnly     = intent.getBooleanExtra("busbar_only", false)
        val cubicleBoxesFlat = intent.getFloatArrayExtra("cubicle_boxes")
        val cubicle_line        = intent.getStringExtra("cubicle_line") ?: ""
        val catalogueGuidance   = intent.getStringExtra("catalogue_guidance") ?: ""

        // Update dashboard stats
        val prefs = getSharedPreferences("google_api_prefs", MODE_PRIVATE)
        val seenPanels = (prefs.getStringSet("panel_types_seen", emptySet()) ?: emptySet())
            .toMutableSet()
            .also { if (panelType.isNotBlank()) it.add(panelType) }
        prefs.edit()
            .putInt("scan_count",           prefs.getInt("scan_count", 0) + 1)
            .putInt("warning_count",        prefs.getInt("warning_count", 0) + warnings.size)
            .putStringSet("panel_types_seen", seenPanels)
            .putLong("last_scan_time_ms",   System.currentTimeMillis())
            .putString("last_panel_type",   panelType.ifBlank { "Unknown Panel" })
            .apply()

        // Show captured image
        val ivResult = findViewById<ImageView>(R.id.ivResult)
        if (imagePath != null) {
            Glide.with(this).load(File(imagePath)).into(ivResult)
        }

        // Show panel identification
        val cardPanel      = findViewById<CardView>(R.id.cardPanel)
        val tvPanelType    = findViewById<TextView>(R.id.tvPanelType)
        val tvPanelSummary = findViewById<TextView>(R.id.tvPanelSummary)
        if (panelType.isNotBlank()) {
            tvPanelType.text     = panelType
            tvPanelSummary.text  = if (busbarOnly && cubicleCount > 0) {
                "Vertical Busbar System Identified\nSegments: $cubicleCount | VBB Position: $vbbPosition"
            } else {
                panelSummary
            }
            cardPanel.visibility = View.VISIBLE
        }

        // Show notes
        val cardNotes = findViewById<CardView>(R.id.cardNotes)
        val tvNotes   = findViewById<TextView>(R.id.tvNotes)
        if (notes.isNotBlank()) {
            tvNotes.text         = notes
            cardNotes.visibility = View.VISIBLE
        }

        // Show safety warnings (slide-based LOTO/PPE/ERMS/arc-flash when work zone drawn)
        val cardWarnings = findViewById<CardView>(R.id.cardWarnings)
        val tvWarnings   = findViewById<TextView>(R.id.tvWarnings)
        if (warnings.isNotEmpty()) {
            tvWarnings.text         = warnings.joinToString("\n\n") { it }
            cardWarnings.visibility = View.VISIBLE
        }

        // Draw bounding boxes + zones after layout is ready
        val overlay = findViewById<BoundingBoxOverlay>(R.id.overlay)
        overlay.post {
            if (detectionsJson != null && imagePath != null) {
                val type       = object : TypeToken<List<Detection>>() {}.type
                val detections = Gson().fromJson<List<Detection>>(detectionsJson, type)

                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(imagePath, opts)
                val orientation = ExifInterface(imagePath)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val (imgW, imgH) = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_ROTATE_270 -> Pair(opts.outHeight, opts.outWidth)
                    else -> Pair(opts.outWidth, opts.outHeight)
                }
                overlay.setDetections(detections, imgW, imgH)

                // Store detections + image size at activity level so tap-to-read
                // can crop the correct region and update the overlay after reading.
                currentDetections.clear()
                currentDetections.addAll(detections)
                imageWidth  = imgW
                imageHeight = imgH

                // Enable tap-to-read: when the user taps a breaker box on the result
                // screen, crop that region from the original photo and send to Gemini
                // for close-up label reading.
                overlay.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val hit = overlay.getDetectionAt(event.x, event.y)
                        if (hit != null) onBreakerTapped(hit)
                    }
                    false   // don't consume — still allows scroll on parent
                }

                val workZone  = workZoneJson?.let { Gson().fromJson(it, ZoneCoords::class.java) }
                val safetyBuf = safetyBufJson?.let { Gson().fromJson(it, ZoneCoords::class.java) }
                overlay.setZones(workZone, safetyBuf)
                overlay.setCubicleCount(cubicleCount)

                // Capture the marked image (photo + zone overlay) for the report
                overlay.post {
                    try {
                        val bmp = Bitmap.createBitmap(ivResult.width, ivResult.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        ivResult.draw(canvas)
                        overlay.draw(canvas)
                        markedBitmap = bmp
                    } catch (e: Exception) { }
                }
            }
        }

        // Draw cubicle boxes — both for Busbar ID mode and Analyze Zone mode
        if (busbarOnly || cubicleBoxesFlat != null) {
            overlay.post {
                cubicleBoxesFlat?.let { overlay.setCubicleBoxes(it) }
                if (vbbBox != null && vbbBox.size >= 4) {
                    overlay.setVbbBox(vbbBox)
                    checkVbbOverlap(vbbBox, workZoneJson, overlay, busbarSide)
                }
            }
        }

        // Show cubicle line info (only in Analyze Zone mode)
        if (cubicle_line.isNotBlank() && !busbarOnly) {
            val cardNotes = findViewById<CardView>(R.id.cardNotes)
            val tvNotes   = findViewById<TextView>(R.id.tvNotes)
            val current   = tvNotes.text.toString()
            tvNotes.text  = if (current.isBlank()) "📍 $cubicle_line"
                            else "$current\n\n📍 $cubicle_line"
            cardNotes.visibility = View.VISIBLE
        }

        // Catalogue guidance — shown only when no work zone was drawn (general scan)
        // When work zone is present, slide-based safety warnings replace it
        if (catalogueGuidance.isNotBlank() && workZoneJson == null) {
            val cardNotes = findViewById<CardView>(R.id.cardNotes)
            val tvNotes   = findViewById<TextView>(R.id.tvNotes)
            val current   = tvNotes.text.toString()
            tvNotes.text  = if (current.isBlank()) "📖 Checklist:\n$catalogueGuidance"
                            else "$current\n\n📖 Checklist:\n$catalogueGuidance"
            cardNotes.visibility = View.VISIBLE
        }

        // QR button — only visible if QR codes were found
        val btnQr = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnQr)
        if (qrCodes.isNotEmpty()) {
            btnQr.visibility = View.VISIBLE
            btnQr.setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("QR Code Data")
                    .setMessage(qrCodes.joinToString("\n\n"))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }


        findViewById<Button>(R.id.btnRetake).setOnClickListener { finish() }

        // Save Report — project details already filled on home screen
        val btnSave = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveReport)
        val btnShare = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShareResult)

        fun generateReport(): File? {
            val prefs         = getSharedPreferences("google_api_prefs", MODE_PRIVATE)
            val projectName   = prefs.getString("project_name",   "") ?: ""
            val siteLocation  = prefs.getString("site_location",  "") ?: ""
            val inspectorName = prefs.getString("inspector_name", "") ?: ""
            val originalBitmap = if (imagePath != null) loadWithExif(imagePath) else null
            return ReportGenerator.generate(
                context        = this,
                markedBitmap   = markedBitmap,
                originalBitmap = originalBitmap,
                projectName    = projectName,
                siteLocation   = siteLocation,
                inspectorName  = inspectorName,
                panelType      = panelType,
                panelSummary   = panelSummary,
                notes          = notes,
                warnings       = warnings,
                busbarOnly     = busbarOnly,
                cubicle_line   = cubicle_line,
                task           = intent.getStringExtra("task") ?: "others"
            )
        }

        btnSave.setOnClickListener {
            try {
                val file = generateReport()!!

                // Persist scan metadata
                val sp = getSharedPreferences("google_api_prefs", MODE_PRIVATE)
                val task      = intent.getStringExtra("task").orEmpty().ifBlank { "others" }
                val inspector = sp.getString("inspector_name", "") ?: ""
                val site      = sp.getString("site_location",  "") ?: ""
                val record = ScanRecord(
                    dateMs         = System.currentTimeMillis(),
                    projectName    = sp.getString("project_name", "") ?: "",
                    siteLocation   = site,
                    inspectorName  = inspector,
                    panelType      = panelType,
                    panelSummary   = panelSummary,
                    notes          = notes,
                    warnings       = warnings,
                    imagePath      = imagePath ?: "",
                    reportFilePath = file.absolutePath,
                    busbarOnly     = busbarOnly,
                    cubicleCount   = cubicleCount,
                    task           = task
                )
                ScanHistoryStore.save(this, record)

                Toast.makeText(this, "Report saved!", Toast.LENGTH_SHORT).show()

                // Open PDF
                val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })


            } catch (e: Exception) {
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        btnShare.setOnClickListener {
            try {
                val file = generateReport()!!
                val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
                startActivity(Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Panel Inspection Report")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to share: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

    }

    private fun checkVbbOverlap(vbbBox: FloatArray, workZoneJson: String?, overlay: BoundingBoxOverlay, side: String) {
        val workZone = workZoneJson?.let {
            try { com.google.gson.Gson().fromJson(it, ZoneCoords::class.java) } catch (e: Exception) { null }
        } ?: return

        // vbbBox is in pixel coords [y1,x1,y2,x2] — check overlap with work zone (0-1000 coords)
        // We need image size to convert. Use overlay dimensions as proxy.
        val imgW = overlay.width.toFloat().coerceAtLeast(1f)
        val imgH = overlay.height.toFloat().coerceAtLeast(1f)
        val vbbXmin = (vbbBox[1] / imgW * 1000).toInt()
        val vbbYmin = (vbbBox[0] / imgH * 1000).toInt()
        val vbbXmax = (vbbBox[3] / imgW * 1000).toInt()
        val vbbYmax = (vbbBox[2] / imgH * 1000).toInt()

        val overlaps = workZone.xmin < vbbXmax && workZone.xmax > vbbXmin &&
                       workZone.ymin < vbbYmax && workZone.ymax > vbbYmin
        if (overlaps) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("⚠ VBB Cubicle Overlap")
                .setMessage(
                    "Your work zone overlaps with the VBB cubicle (${side.uppercase()} side).\n\n" +
                    "Live busbars are present inside this cubicle.\n\n" +
                    "• Do NOT open or penetrate this cubicle\n" +
                    "• Isolate main supply before working here\n" +
                    "• Verify de-energisation before proceeding"
                )
                .setPositiveButton("Understood", null)
                .show()
        }
    }

    private fun checkBusbarOverlap(side: String, workZoneJson: String?, overlay: BoundingBoxOverlay) {
        // Always draw the busbar zone on screen
        overlay.setBusbarSide(side)

        // Busbar zone in 0-1000 coordinates (18% of panel width/height)
        val busbarXmin = if (side == "left")  0   else 820
        val busbarXmax = if (side == "left")  180 else 1000
        val busbarYmin = 0
        val busbarYmax = 1000

        // Check if work zone overlaps with busbar zone
        val workZone = workZoneJson?.let {
            try { com.google.gson.Gson().fromJson(it, ZoneCoords::class.java) } catch (e: Exception) { null }
        }

        if (workZone != null) {
            val overlaps = workZone.xmin < busbarXmax && workZone.xmax > busbarXmin &&
                           workZone.ymin < busbarYmax && workZone.ymax > busbarYmin

            if (overlaps) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("⚠ Busbar Zone Overlap")
                    .setMessage(
                        "Your work zone overlaps with the busbar compartment (${side.uppercase()} side).\n\n" +
                        "Live busbars may be present behind this area.\n\n" +
                        "• Do NOT drill or penetrate this section\n" +
                        "• Isolate the main supply before working here\n" +
                        "• Verify de-energisation before proceeding"
                    )
                    .setPositiveButton("Understood", null)
                    .show()
            }
            // If no overlap — no warning needed, busbar zone is just shown on screen
        }
    }

    /**
     * onBreakerTapped()
     *
     * Called when the user taps a breaker bounding box on the result screen.
     *
     * Flow:
     *   1. Show a dialog immediately with "Reading label..."
     *   2. On a background thread: load the original image → crop the breaker
     *      region (with 20% padding) → send to /api/read_label on the server.
     *   3. Gemini reads any text on the breaker face or label strip close-up.
     *   4. Update the dialog text with the result.
     *   5. If new label/rating was found, update currentDetections and refresh
     *      the overlay so the sub-label tag below the bounding box updates.
     *
     * This is the tap-to-read fallback (Idea 3): useful when the photo was taken
     * from too far away for Gemini to read labels during the main scan.
     */
    private fun onBreakerTapped(detection: Detection) {
        val imgPath = currentImagePath ?: return

        // Show a dialog immediately so the user sees something happened
        val dialog = AlertDialog.Builder(this)
            .setTitle(detection.label)
            .setMessage("Reading label...")
            .setPositiveButton("OK", null)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            val full = loadWithExif(imgPath) ?: run {
                withContext(Dispatchers.Main) {
                    dialog.setMessage("Could not load image.")
                }
                return@launch
            }

            // Crop the breaker region with 20% padding on each side so
            // the label strip beside the breaker is also included in the crop.
            val padX = (detection.x2 - detection.x1) * 0.2f
            val padY = (detection.y2 - detection.y1) * 0.2f
            val cropX1 = (detection.x1 - padX).coerceAtLeast(0f).toInt()
            val cropY1 = (detection.y1 - padY).coerceAtLeast(0f).toInt()
            val cropX2 = (detection.x2 + padX).coerceAtMost(full.width.toFloat()).toInt()
            val cropY2 = (detection.y2 + padY).coerceAtMost(full.height.toFloat()).toInt()
            val cropW  = (cropX2 - cropX1).coerceAtLeast(1)
            val cropH  = (cropY2 - cropY1).coerceAtLeast(1)

            val cropped = Bitmap.createBitmap(full, cropX1, cropY1, cropW, cropH)
            full.recycle()

            val (label, rating) = detector.readLabel(cropped)
            cropped.recycle()

            withContext(Dispatchers.Main) {
                // Build the result message
                val msg = when {
                    label.isNotBlank() && rating.isNotBlank() ->
                        "Circuit:  $label\nRating:    $rating"
                    label.isNotBlank()  -> "Circuit:  $label"
                    rating.isNotBlank() -> "Rating:    $rating"
                    else ->
                        "No label text detected.\n\nFor best results, zoom in close to the breaker before taking the photo."
                }
                dialog.setMessage(msg)

                // If new info was read, update the detection in the list and
                // refresh the overlay so the sub-label tag updates on screen.
                if (label.isNotBlank() || rating.isNotBlank()) {
                    val idx = currentDetections.indexOfFirst { d ->
                        d.x1 == detection.x1 && d.y1 == detection.y1
                    }
                    if (idx >= 0) {
                        val old = currentDetections[idx]
                        currentDetections[idx] = old.copy(
                            circuitLabel = label.ifBlank { old.circuitLabel },
                            rating       = rating.ifBlank { old.rating }
                        )
                        val overlay = findViewById<BoundingBoxOverlay>(R.id.overlay)
                        overlay.setDetections(currentDetections, imageWidth, imageHeight)
                    }
                }
            }
        }
    }

    private fun loadWithExif(path: String): Bitmap? {
        val bmp  = BitmapFactory.decodeFile(path) ?: return null
        val exif = ExifInterface(path)
        val deg  = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90  ->  90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return bmp
        val m = android.graphics.Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { bmp.recycle() }
    }
}
