package com.example.testerapigoogle

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException


class WorkZoneActivity : AppCompatActivity() {

    private val detector = GoogleStudioDetector()

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
        val m = Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { bmp.recycle() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_work_zone)

        val imagePath  = intent.getStringExtra("image_path") ?: run { finish(); return }
        val task       = intent.getStringExtra("task") ?: "others"
        val imageView  = findViewById<ImageView>(R.id.ivWorkZone)
        val overlay    = findViewById<WorkZoneOverlay>(R.id.workZoneOverlay)
        val btnAnalyze = findViewById<MaterialButton>(R.id.btnAnalyze)
        val tvHint     = findViewById<TextView>(R.id.tvHint)

        Glide.with(this).load(File(imagePath)).into(imageView)

        // Get image dimensions accounting for EXIF rotation
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imagePath, opts)
        val orientation = ExifInterface(imagePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val (imgW, imgH) = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270 -> Pair(opts.outHeight, opts.outWidth)
            else -> Pair(opts.outWidth, opts.outHeight)
        }
        overlay.post { overlay.setImageSize(imgW, imgH) }

        overlay.onZoneChanged = { _, _ ->
            btnAnalyze.isEnabled = true
            tvHint.text = "Zone selected — tap Analyze"
        }

        btnAnalyze.setOnClickListener {
            val zones = overlay.getZones() ?: run {
                Toast.makeText(this, "Draw a work zone first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runDetection(imagePath, zones.first, zones.second, task = task)
        }

        findViewById<MaterialButton>(R.id.btnIdentifyPanel)
            .setOnClickListener { runDetection(imagePath, null, null, identifyOnly = true, task = task) }

    }

    private val handler = Handler(Looper.getMainLooper())
    private var messageRunnable: Runnable? = null

    private fun startMessages(tvStatus: TextView, tvSub: TextView) {
        tvStatus.text = "Analysing panel..."
        tvSub.text = "This usually takes a few seconds"
        messageRunnable = Runnable {
            tvSub.text = "Almost there..."
        }
        handler.postDelayed(messageRunnable!!, 5000)
    }

    private fun stopMessages() {
        messageRunnable?.let { handler.removeCallbacks(it) }
        messageRunnable = null
    }

    private fun friendlyError(throwable: Throwable): Pair<String, String> {
        val msg = throwable.message ?: ""
        return when {
            throwable is ConnectException || throwable is UnknownHostException ->
                Pair("Server Not Reachable", "Make sure the Python server is running on your Mac and both devices are on the same Wi-Fi network.")
            throwable is SocketTimeoutException ->
                Pair("Request Timed Out", "The server took too long to respond. Check your Wi-Fi connection and try again.")
            msg.contains("429") || msg.contains("quota", ignoreCase = true) ->
                Pair("AI Quota Reached", "The free daily quota for Gemini AI has been used up. It resets at midnight Pacific time (or get a new API key from ai.google.dev).")
            msg.contains("503") || msg.contains("overload", ignoreCase = true) ->
                Pair("AI Temporarily Busy", "Gemini AI is overloaded right now. Wait a minute and try again.")
            msg.contains("404") ->
                Pair("Model Not Found", "The AI model name in server.py may be wrong. Check NOTES.md for the correct model ID.")
            else ->
                Pair("Something Went Wrong", "Error: $msg\n\nCheck that the server is running and the IP address is correct in GoogleStudioDetector.kt.")
        }
    }

    private fun runDetection(
        imagePath: String,
        workZone: ZoneCoords?,
        safetyBuffer: ZoneCoords?,
        identifyOnly: Boolean = false,
        busbarOnly: Boolean = false,
        task: String = "others"
    ) {
        val progress    = findViewById<LinearLayout>(R.id.layoutProgress)
        val tvStatus    = findViewById<TextView>(R.id.tvProgressStatus)
        val tvSub       = findViewById<TextView>(R.id.tvProgressSub)
        val btnAnalyze  = findViewById<MaterialButton>(R.id.btnAnalyze)
        val btnIdentify = findViewById<MaterialButton>(R.id.btnIdentifyPanel)

        progress.visibility   = View.VISIBLE
        btnAnalyze.isEnabled  = false
        btnIdentify.isEnabled = false

        startMessages(tvStatus, tvSub)

        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = loadWithExif(imagePath)

            if (bitmap == null) {
                withContext(Dispatchers.Main) {
                    stopMessages()
                    progress.visibility   = View.GONE
                    btnAnalyze.isEnabled  = true
                    btnIdentify.isEnabled = true
                    Toast.makeText(this@WorkZoneActivity, "Failed to load image", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val prefs        = getSharedPreferences("google_api_prefs", MODE_PRIVATE)
            val loginPrefs   = getSharedPreferences("login_prefs", MODE_PRIVATE)
            val sldPath      = prefs.getString("sld_image_path", null)
            val layoutPath   = prefs.getString("layout_image_path", null)
            val sldBitmap    = sldPath?.let    { BitmapFactory.decodeFile(it) }
            val layoutBitmap = layoutPath?.let { BitmapFactory.decodeFile(it) }
            val username     = loginPrefs.getString("username", "") ?: ""
            val projectName  = prefs.getString("project_name", "") ?: ""
            val site         = prefs.getString("site_location", "") ?: ""
            val inspector    = prefs.getString("inspector_name", "") ?: ""

            try {
                val result = detector.detect(bitmap, workZone, safetyBuffer, identifyOnly, busbarOnly, sldBitmap, layoutBitmap, task, username, projectName, site, inspector)

                withContext(Dispatchers.Main) {
                    stopMessages()
                    progress.visibility = View.GONE
                    startActivity(Intent(this@WorkZoneActivity, ResultActivity::class.java).apply {
                        putExtra("image_path",       imagePath)
                        putExtra("detections_json",  Gson().toJson(result.detections))
                        putExtra("notes",            result.notes)
                        putExtra("safety_warnings",  ArrayList(result.safetyWarnings))
                        if (workZone != null)     putExtra("work_zone",     Gson().toJson(workZone))
                        if (safetyBuffer != null) putExtra("safety_buffer", Gson().toJson(safetyBuffer))
                        putExtra("panel_type",       result.panelType)
                        putExtra("panel_summary",    result.panelSummary)
                        putExtra("qr_codes",         ArrayList(result.qrCodes))
                        putExtra("busbar_side",      result.busbarSide)
                        result.vbbBox?.let { putExtra("vbb_box", it) }
                        putExtra("cubicle_count",    result.cubicleCount)
                        putExtra("vbb_position",     result.vbbPosition)
                        if (result.cubicles.isNotEmpty()) {
                            // Pack all cubicle boxes as flat float array: [y1,x1,y2,x2, y1,x1,y2,x2, ...]
                            val flat = result.cubicles.flatMap { it.box.toList() }.toFloatArray()
                            putExtra("cubicle_boxes", flat)
                            putExtra("cubicle_count_actual", result.cubicles.size)
                        }
                        putExtra("busbar_only",          busbarOnly)
                        putExtra("cubicle_line",         result.cubicle_line)
                        putExtra("task",                 task)
                        putExtra("catalogue_guidance",   result.catalogueGuidance)
                    })
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    stopMessages()
                    progress.visibility   = View.GONE
                    btnAnalyze.isEnabled  = true
                    btnIdentify.isEnabled = true

                    val (title, message) = friendlyError(e)
                    AlertDialog.Builder(this@WorkZoneActivity)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMessages()
    }
}
