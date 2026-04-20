package com.example.testerapigoogle

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class VerifyPanelActivity : AppCompatActivity() {

    private var workerPhotoUri: Uri? = null
    private var workerPhotoFile: File? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val REQUEST_CAMERA = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verify_panel)

        val refImagePath = intent.getStringExtra("reference_image_path")

        // Show reference image
        val ivReference = findViewById<ImageView>(R.id.ivReference)
        if (refImagePath != null) {
            ivReference.setImageBitmap(BitmapFactory.decodeFile(refImagePath))
        }

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btnTakePhoto).setOnClickListener {
            launchCamera()
        }

        findViewById<MaterialButton>(R.id.btnVerifyNow).setOnClickListener {
            val workerPath = workerPhotoFile?.absolutePath ?: return@setOnClickListener
            if (refImagePath != null) {
                runVerification(refImagePath, workerPath)
            }
        }
    }

    private fun launchCamera() {
        val photoFile = File(cacheDir, "verify_worker_${System.currentTimeMillis()}.jpg")
        workerPhotoFile = photoFile
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", photoFile)
        workerPhotoUri = uri
        startActivityForResult(
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply { putExtra(MediaStore.EXTRA_OUTPUT, uri) },
            REQUEST_CAMERA
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAMERA && resultCode == RESULT_OK) {
            val file = workerPhotoFile ?: return
            if (file.exists()) {
                findViewById<ImageView>(R.id.ivWorkerPhoto).setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                findViewById<MaterialButton>(R.id.btnVerifyNow).isEnabled = true
                findViewById<TextView>(R.id.tvResult).visibility = View.GONE
            }
        }
    }

    private fun runVerification(refPath: String, workerPath: String) {
        val btnVerify  = findViewById<MaterialButton>(R.id.btnVerifyNow)
        val btnPhoto   = findViewById<MaterialButton>(R.id.btnTakePhoto)
        val tvResult   = findViewById<TextView>(R.id.tvResult)
        val layoutProgress = findViewById<LinearLayout>(R.id.layoutProgress)

        btnVerify.isEnabled  = false
        btnPhoto.isEnabled   = false
        layoutProgress.visibility = View.VISIBLE
        tvResult.visibility  = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val refB64    = bitmapToBase64(BitmapFactory.decodeFile(refPath))
                val workerB64 = bitmapToBase64(BitmapFactory.decodeFile(workerPath))

                val serverUrl = GoogleStudioDetector.BASE_URL
                    .replace("/api/analyze", "/api/verify_panel")

                val body = JSONObject().apply {
                    put("referenceBase64", refB64)
                    put("workerBase64",    workerB64)
                    put("mimeType",        "image/jpeg")
                }.toString().toRequestBody("application/json".toMediaType())

                val response = client.newCall(
                    Request.Builder().url(serverUrl).post(body).build()
                ).execute()

                val json   = JSONObject(response.body?.string() ?: "{}")
                val match  = json.optBoolean("match", false)
                val reason = json.optString("reason", "")
                val conf   = json.optString("confidence", "")

                withContext(Dispatchers.Main) {
                    layoutProgress.visibility = View.GONE
                    btnPhoto.isEnabled  = true
                    btnVerify.isEnabled = true
                    tvResult.visibility = View.VISIBLE

                    if (match) {
                        tvResult.text = "✅ CORRECT PANEL\n\n$reason\n\nConfidence: $conf"
                        tvResult.setBackgroundColor(0xFF1B5E20.toInt())
                        tvResult.setTextColor(0xFFFFFFFF.toInt())
                    } else {
                        tvResult.text = "⛔ WRONG PANEL — DO NOT WORK HERE\n\n$reason\n\nConfidence: $conf"
                        tvResult.setBackgroundColor(0xFFB71C1C.toInt())
                        tvResult.setTextColor(0xFFFFFFFF.toInt())
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    layoutProgress.visibility = View.GONE
                    btnPhoto.isEnabled  = true
                    btnVerify.isEnabled = true
                    tvResult.visibility = View.VISIBLE
                    tvResult.text = "⚠️ Could not verify: ${e.message}"
                    tvResult.setBackgroundColor(0xFFE65100.toInt())
                    tvResult.setTextColor(0xFFFFFFFF.toInt())
                }
            }
        }
    }

    private fun bitmapToBase64(bmp: Bitmap): String {
        // Downscale to 1024px max — enough for panel comparison
        val maxSide = 1024f
        val scale   = minOf(maxSide / bmp.width, maxSide / bmp.height, 1f)
        val scaled  = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
