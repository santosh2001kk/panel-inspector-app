package com.example.testerapigoogle

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocateVbbActivity : AppCompatActivity() {

    private var currentStep = 1
    private var panelImageFile: File? = null
    private var nameplateImageFile: File? = null
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_locate_vbb)

        startCamera()

        findViewById<FloatingActionButton>(R.id.fabCapture).setOnClickListener { capturePhoto() }
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener {
            if (currentStep == 2) {
                currentStep = 1
                panelImageFile = null
                updateStepUi()
            } else {
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val ic = imageCapture ?: return
        val name = "vbb_step${currentStep}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val file = File(getExternalFilesDir(null), name)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
        ic.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                runOnUiThread {
                    if (currentStep == 1) {
                        panelImageFile = file
                        currentStep = 2
                        updateStepUi()
                    } else {
                        nameplateImageFile = file
                        callServer()
                    }
                }
            }
            override fun onError(exc: ImageCaptureException) {
                runOnUiThread {
                    Toast.makeText(this@LocateVbbActivity, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun updateStepUi() {
        val tvStep    = findViewById<TextView>(R.id.tvStepLabel)
        val tvInstr   = findViewById<TextView>(R.id.tvInstruction)
        val tvSub     = findViewById<TextView>(R.id.tvSubInstruction)
        val cardThumb = findViewById<CardView>(R.id.cardThumb)
        val ivThumb   = findViewById<ImageView>(R.id.ivThumb)
        val tvThumbLbl = findViewById<TextView>(R.id.tvThumbLabel)
        val dot2      = findViewById<View>(R.id.dot2)

        if (currentStep == 2) {
            tvStep.text  = "STEP 2 OF 2"
            tvInstr.text = "Take CLOSE-UP of MTZ nameplate"
            tvSub.text   = "Focus on the rating label on the front of the breaker"
            dot2.backgroundTintList = ContextCompat.getColorStateList(this, R.color.schneider_green)
            cardThumb.visibility  = View.VISIBLE
            tvThumbLbl.visibility = View.VISIBLE
            panelImageFile?.let {
                ivThumb.setImageBitmap(BitmapFactory.decodeFile(it.absolutePath))
            }
        } else {
            tvStep.text  = "STEP 1 OF 2"
            tvInstr.text = "Take photo of the FULL CLOSED PANEL"
            tvSub.text   = "Make sure both doors are fully visible"
            dot2.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.darker_gray)
            cardThumb.visibility  = View.GONE
            tvThumbLbl.visibility = View.GONE
        }
    }

    private fun callServer() {
        val panelFile     = panelImageFile     ?: return
        val nameplateFile = nameplateImageFile ?: return

        val progressLayout = findViewById<LinearLayout>(R.id.layoutProgress)
        val tvStatus       = findViewById<TextView>(R.id.tvProgressStatus)
        progressLayout.visibility = View.VISIBLE
        tvStatus.text = "Reading MTZ nameplate..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val panelB64     = Base64.encodeToString(panelFile.readBytes(), Base64.NO_WRAP)
                val nameplateB64 = Base64.encodeToString(nameplateFile.readBytes(), Base64.NO_WRAP)

                val body = JSONObject().apply {
                    put("panelImageBase64",     panelB64)
                    put("nameplateImageBase64", nameplateB64)
                    put("mimeType", "image/jpeg")
                }.toString()

                withContext(Dispatchers.Main) { tvStatus.text = "Locating VBB compartment..." }

                val serverUrl = GoogleStudioDetector.BASE_URL.replace("/api/analyze", "/api/locate_vbb")
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(serverUrl)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: throw Exception("Empty response")

                if (!response.isSuccessful) throw Exception("Server error ${response.code}: $responseBody")

                val result = JSONObject(responseBody)

                withContext(Dispatchers.Main) {
                    progressLayout.visibility = View.GONE
                    val intent = Intent(this@LocateVbbActivity, VbbResultActivity::class.java).apply {
                        putExtra("panel_image_path",  panelFile.absolutePath)
                        putExtra("mtz_model",         result.optString("mtz_model", "Unknown"))
                        putExtra("rated_current_A",   result.optInt("rated_current_A", 0))
                        putExtra("vbb_side",          result.optString("vbb_side", "unknown"))
                        putExtra("vbb_width_mm",      result.optInt("vbb_width_mm", 0))
                        putExtra("busbar_rating",     result.optString("busbar_rating", ""))
                        putExtra("confidence",        result.optString("confidence", "low"))
                        putExtra("notes",             result.optString("notes", ""))
                        putExtra("safety_warning",    result.optString("safety_warning", ""))
                        val boxPx = result.optJSONArray("vbb_box_px")
                        if (boxPx != null && boxPx.length() >= 4) {
                            putExtra("vbb_box_px", intArrayOf(
                                boxPx.getInt(0), boxPx.getInt(1),
                                boxPx.getInt(2), boxPx.getInt(3)
                            ))
                        }
                    }
                    startActivity(intent)
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressLayout.visibility = View.GONE
                    Toast.makeText(this@LocateVbbActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
