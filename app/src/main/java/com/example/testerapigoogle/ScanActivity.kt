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
import android.view.ScaleGestureDetector
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/**
 * ScanActivity
 *
 * The camera screen where the user takes a photo of the electrical panel.
 *
 * Flow:
 *   1. Camera permission is checked on launch — if not granted, it is requested.
 *   2. The live camera preview is shown via CameraX PreviewView.
 *   3. User can pinch-to-zoom on the preview to get closer to panel labels.
 *   4. User taps the capture FAB → photo is saved as a timestamped JPEG file.
 *   5. The image is quality-checked (blur, brightness).
 *      → If it fails: a dialog warns the user and offers to retake or continue.
 *      → If it passes: moves to WorkZoneActivity.
 *   6. Alternatively, user can pick an image from the gallery.
 *
 * The task string (e.g. "maintenance") is passed in via the Intent extra "task"
 * from TaskSelectionActivity. It is forwarded to WorkZoneActivity and eventually
 * to the server to determine which checklist to return.
 */
class ScanActivity : AppCompatActivity() {

    // CameraX ImageCapture use-case — used to trigger the actual photo capture
    private var imageCapture: ImageCapture? = null

    // CameraX Camera object — needed to control zoom via cameraControl
    private var camera: Camera? = null

    // Background thread executor for CameraX internal operations
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // Gallery image picker — launched when the user taps the gallery FAB.
    // Copies the selected image to internal storage so the rest of the flow
    // works the same way as a captured photo.
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

        // Start the camera if permission is already granted,
        // otherwise ask the user for it (result handled in onRequestPermissionsResult)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }

        // Capture button — takes the photo
        findViewById<FloatingActionButton>(R.id.fabCapture).setOnClickListener { capture() }

        // Gallery button — lets the user pick an existing photo instead
        findViewById<FloatingActionButton>(R.id.fabGallery).setOnClickListener {
            galleryLauncher.launch("image/*")
        }

        // ── Pinch-to-zoom ─────────────────────────────────────────────────────
        // ScaleGestureDetector tracks two-finger pinch gestures on the preview.
        // When a pinch is detected, onScale() fires repeatedly as the fingers move.
        //   detector.scaleFactor > 1.0 = fingers spreading apart  = zoom in
        //   detector.scaleFactor < 1.0 = fingers pinching together = zoom out
        // We multiply the current zoom ratio by the scale factor each frame to
        // get smooth continuous zoom. CameraX clamps the value to the camera's
        // supported zoom range automatically.
        val viewFinder = findViewById<PreviewView>(R.id.viewFinder)
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return true
                    val currentZoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    cam.cameraControl.setZoomRatio(currentZoom * detector.scaleFactor)
                    return true
                }
            }
        )
        // Attach the gesture detector to the preview view's touch events
        viewFinder.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            v.performClick()
            true
        }

        // Photo guide link — tapping the frame hint text opens the guide
        findViewById<android.widget.TextView>(R.id.tvFrameGuide).setOnClickListener {
            startActivity(Intent(this, PhotoGuideActivity::class.java))
        }

        // Auto-show the photo guide on the very first launch ever
        val prefs = getSharedPreferences("google_api_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("photo_guide_seen", false)) {
            startActivity(Intent(this, PhotoGuideActivity::class.java))
        }
    }

    /**
     * startCamera()
     *
     * Initialises CameraX and binds two use-cases to the activity lifecycle:
     *   - Preview: streams frames to the PreviewView on screen
     *   - ImageCapture: used to take a still photo when the user taps capture
     *
     * The returned Camera object is stored in `camera` so that pinch-to-zoom
     * can control its zoom ratio via cameraControl.
     */
    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            val provider = ProcessCameraProvider.getInstance(this).get()
            val preview  = Preview.Builder().build()
                .also { it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider) }
            imageCapture = ImageCapture.Builder().build()
            provider.unbindAll()
            // bindToLifecycle returns the Camera object — save it for zoom control
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * capture()
     *
     * Saves the current camera frame to a timestamped JPEG file in the app's
     * external files directory, then passes it to runDetection().
     */
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

    /**
     * checkQuality()
     *
     * Performs two quick checks on the captured image before sending it to the server:
     *
     *   1. Brightness check — samples every 10th pixel and computes average luminance.
     *      Returns "too_dark"   if average < 50  (underexposed)
     *      Returns "too_bright" if average > 220 (overexposed)
     *
     *   2. Blur check — applies a Laplacian edge filter to the centre 50% of the image.
     *      Low variance in Laplacian output = blurry image.
     *      Returns "blurry" if variance < 80.0
     *
     * Returns null if the image passes both checks.
     * Called on a background thread inside runDetection().
     */
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

    /**
     * proceedToWorkZone()
     *
     * Navigates to WorkZoneActivity, passing the saved image file path and the
     * task type. WorkZoneActivity lets the user draw a work zone on the image
     * before it is sent to the server for analysis.
     */
    private fun proceedToWorkZone(file: File) {
        val task = intent.getStringExtra("task") ?: "others"
        startActivity(Intent(this@ScanActivity, WorkZoneActivity::class.java).apply {
            putExtra("image_path", file.absolutePath)
            putExtra("task", task)
        })
    }

    /**
     * runDetection()
     *
     * Called after a photo is captured or selected from gallery.
     * Runs the quality check on a background thread, then either:
     *   - Shows a warning dialog (with option to retake or continue anyway), or
     *   - Proceeds directly to WorkZoneActivity if quality is acceptable.
     *
     * The progress spinner is shown and the capture button is disabled while
     * the quality check runs to prevent double-tapping.
     */
    private fun runDetection(file: File) {
        val progress = findViewById<LinearLayout>(R.id.layoutProgress)
        val fab      = findViewById<FloatingActionButton>(R.id.fabCapture)
        progress.visibility = View.VISIBLE
        fab.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            // Load the JPEG with EXIF rotation correction so it's always upright
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
                    // Quality check failed — show a warning dialog
                    val (title, message) = when (issue) {
                        "too_dark"   -> Pair("Image Too Dark",    "The image is underexposed. Move to a brighter area or turn on a torch for better results.")
                        "too_bright" -> Pair("Image Too Bright",  "The image is overexposed. Avoid direct light sources pointing at the camera.")
                        "blurry"     -> Pair("Image May Be Blurry","The image appears blurry. Hold the phone steady and ensure the panel is in focus.")
                        else         -> Pair("Image Quality Warning", "The image quality may affect detection accuracy.")
                    }
                    AlertDialog.Builder(this@ScanActivity)
                        .setTitle(title)
                        .setMessage("$message\n\nYou can retake for better results, or continue anyway.")
                        .setPositiveButton("Retake") { _, _ -> /* user closes dialog and retakes */ }
                        .setNegativeButton("Continue Anyway") { _, _ -> proceedToWorkZone(file) }
                        .show()
                } else {
                    // Quality is good — go straight to the work zone screen
                    proceedToWorkZone(file)
                }
            }
        }
    }

    /**
     * loadWithExif()
     *
     * Loads a JPEG file as a Bitmap and corrects its rotation using the EXIF
     * orientation tag. Android camera apps often save photos with rotation
     * metadata instead of physically rotating the pixels. Without this fix,
     * the image would appear sideways or upside-down when displayed or analysed.
     *
     * Returns the correctly-rotated Bitmap, or null if the file cannot be decoded.
     */
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
        if (deg == 0f) return bmp   // already upright — no rotation needed
        val m = Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            .also { bmp.recycle() }  // free the unrotated original to save memory
    }

    // Called by Android after requestPermissions() — starts the camera if granted
    override fun onRequestPermissionsResult(rc: Int, perms: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(rc, perms, grants)
        if (rc == 10 && grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
    }

    // Shut down the camera background thread when the activity is destroyed
    override fun onDestroy() { super.onDestroy(); cameraExecutor.shutdown() }
}
