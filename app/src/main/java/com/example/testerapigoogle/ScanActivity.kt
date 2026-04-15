package com.example.testerapigoogle

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class ScanActivity : AppCompatActivity() {

    private var imageCapture: ImageCapture? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stream = contentResolver.openInputStream(uri) ?: return@launch
                val file = File(getExternalFilesDir(null),
                    "gallery_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg")
                FileOutputStream(file).use { stream.copyTo(it) }
                withContext(Dispatchers.Main) { runDetection(file) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ScanActivity, "Failed to load image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }

        findViewById<FloatingActionButton>(R.id.fabCapture).setOnClickListener { capture() }
        findViewById<FloatingActionButton>(R.id.fabGallery).setOnClickListener {
            galleryLauncher.launch("image/*")
        }
        findViewById<android.widget.TextView>(R.id.tvFrameGuide).setOnClickListener {
            startActivity(Intent(this, PhotoGuideActivity::class.java))
        }

        // Show photo guide automatically on first ever launch
        val prefs = getSharedPreferences("google_api_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("photo_guide_seen", false)) {
            startActivity(Intent(this, PhotoGuideActivity::class.java))
        }
    }

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            val provider = ProcessCameraProvider.getInstance(this).get()
            val preview  = Preview.Builder().build()
                .also { it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider) }
            imageCapture = ImageCapture.Builder().build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        val ic   = imageCapture ?: return
        val file = File(getExternalFilesDir(null),
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg")

        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(e: ImageCaptureException) {
                    Toast.makeText(this@ScanActivity, "Capture failed", Toast.LENGTH_SHORT).show()
                }
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    runDetection(file)
                }
            })
    }

    private fun checkQuality(bitmap: Bitmap): String? {
        // Sample every 10th pixel for performance
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Brightness check
        var brightnessSum = 0.0
        var count = 0
        for (i in pixels.indices step 10) {
            val p = pixels[i]
            brightnessSum += Color.red(p) * 0.299 + Color.green(p) * 0.587 + Color.blue(p) * 0.114
            count++
        }
        val brightness = brightnessSum / count
        if (brightness < 50) return "too_dark"
        if (brightness > 220) return "too_bright"

        // Blur check using Laplacian variance on center region
        val cx = w / 2; val cy = h / 2
        val rx = w / 4; val ry = h / 4
        var lap = 0.0; var lapSq = 0.0; var lapCount = 0
        for (y in (cy - ry) until (cy + ry) step 4) {
            for (x in (cx - rx) until (cx + rx) step 4) {
                if (x < 1 || x >= w - 1 || y < 1 || y >= h - 1) continue
                fun gray(px: Int) = Color.red(px) * 0.299 + Color.green(px) * 0.587 + Color.blue(px) * 0.114
                val l = 4 * gray(pixels[y * w + x]) -
                        gray(pixels[(y-1) * w + x]) - gray(pixels[(y+1) * w + x]) -
                        gray(pixels[y * w + (x-1)]) - gray(pixels[y * w + (x+1)])
                lap += l; lapSq += l * l; lapCount++
            }
        }
        if (lapCount > 0) {
            val mean = lap / lapCount
            val variance = lapSq / lapCount - mean * mean
            if (variance < 80.0) return "blurry"
        }
        return null
    }

    private fun proceedToWorkZone(file: File) {
        val task = intent.getStringExtra("task") ?: "others"
        startActivity(Intent(this@ScanActivity, WorkZoneActivity::class.java).apply {
            putExtra("image_path", file.absolutePath)
            putExtra("task", task)
        })
    }

    private fun runDetection(file: File) {
        val progress = findViewById<LinearLayout>(R.id.layoutProgress)
        val fab      = findViewById<FloatingActionButton>(R.id.fabCapture)
        progress.visibility = View.VISIBLE
        fab.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = loadWithExif(file.absolutePath) ?: run {
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    fab.isEnabled = true
                    Toast.makeText(this@ScanActivity, "Failed to load image", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val issue = checkQuality(bitmap)

            withContext(Dispatchers.Main) {
                progress.visibility = View.GONE
                fab.isEnabled = true

                if (issue != null) {
                    val (title, message) = when (issue) {
                        "too_dark"  -> Pair("Image Too Dark", "The image is underexposed. Move to a brighter area or turn on a torch for better results.")
                        "too_bright" -> Pair("Image Too Bright", "The image is overexposed. Avoid direct light sources pointing at the camera.")
                        "blurry"    -> Pair("Image May Be Blurry", "The image appears blurry. Hold the phone steady and ensure the panel is in focus.")
                        else        -> Pair("Image Quality Warning", "The image quality may affect detection accuracy.")
                    }
                    AlertDialog.Builder(this@ScanActivity)
                        .setTitle(title)
                        .setMessage("$message\n\nYou can retake for better results, or continue anyway.")
                        .setPositiveButton("Retake") { _, _ -> /* dismiss — user retakes */ }
                        .setNegativeButton("Continue Anyway") { _, _ -> proceedToWorkZone(file) }
                        .show()
                } else {
                    proceedToWorkZone(file)
                }
            }
        }
    }

    private fun loadWithExif(path: String): Bitmap? {
        val bmp  = BitmapFactory.decodeFile(path) ?: return null
        val exif = ExifInterface(path)
        val deg  = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                                              ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90  ->  90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return bmp
        val m = Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            .also { bmp.recycle() }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(rc, perms, grants)
        if (rc == 10 && grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() { super.onDestroy(); cameraExecutor.shutdown() }
}
