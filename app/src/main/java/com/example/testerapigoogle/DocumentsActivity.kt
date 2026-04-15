package com.example.testerapigoogle

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream

class DocumentsActivity : AppCompatActivity() {

    private var pickingSlot: String = ""

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val destFile = copyToInternal(uri, pickingSlot)
        if (destFile != null) {
            getSharedPreferences("google_api_prefs", MODE_PRIVATE)
                .edit().putString(pickingSlot, destFile.absolutePath).apply()
            refreshUI()
            Toast.makeText(this, "Image saved", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_documents)
        supportActionBar?.hide()

        val flowMode = intent.getBooleanExtra("flow_mode", false)

        findViewById<MaterialButton>(R.id.btnBackDocuments).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btnPickSld).setOnClickListener {
            pickingSlot = "sld_image_path"
            pickImage.launch("image/*")
        }

        findViewById<MaterialButton>(R.id.btnPickLayout).setOnClickListener {
            pickingSlot = "layout_image_path"
            pickImage.launch("image/*")
        }

        findViewById<MaterialButton>(R.id.btnClearSld).setOnClickListener {
            clearSlot("sld_image_path")
        }

        findViewById<MaterialButton>(R.id.btnClearLayout).setOnClickListener {
            clearSlot("layout_image_path")
        }

        // In flow mode — show Next button that goes to Task Selection
        val btnNext = findViewById<MaterialButton?>(R.id.btnNextDocuments)
        if (flowMode && btnNext != null) {
            btnNext.visibility = android.view.View.VISIBLE
            btnNext.setOnClickListener {
                startActivity(Intent(this, TaskSelectionActivity::class.java))
            }
        }

        refreshUI()
    }

    private fun copyToInternal(uri: Uri, slot: String): File? {
        return try {
            val ext  = contentResolver.getType(uri)?.substringAfter("/") ?: "jpg"
            val name = "${slot}.${ext}"
            val dest = File(filesDir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            dest
        } catch (e: Exception) {
            null
        }
    }

    private fun clearSlot(slot: String) {
        val prefs = getSharedPreferences("google_api_prefs", MODE_PRIVATE)
        val path  = prefs.getString(slot, null)
        if (path != null) File(path).delete()
        prefs.edit().remove(slot).apply()
        refreshUI()
        Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
    }

    private fun refreshUI() {
        val prefs = getSharedPreferences("google_api_prefs", MODE_PRIVATE)

        val sldPath    = prefs.getString("sld_image_path", null)
        val layoutPath = prefs.getString("layout_image_path", null)

        val ivSld       = findViewById<ImageView>(R.id.ivSldThumb)
        val tvSldStatus = findViewById<TextView>(R.id.tvSldStatus)
        val ivLayout       = findViewById<ImageView>(R.id.ivLayoutThumb)
        val tvLayoutStatus = findViewById<TextView>(R.id.tvLayoutStatus)

        if (sldPath != null && File(sldPath).exists()) {
            val bmp = BitmapFactory.decodeFile(sldPath)
            if (bmp != null) ivSld.setImageBitmap(bmp)
            tvSldStatus.text = "Uploaded — ${File(sldPath).name}"
        } else {
            ivSld.setImageResource(android.R.drawable.ic_menu_gallery)
            tvSldStatus.text = "Not uploaded"
        }

        if (layoutPath != null && File(layoutPath).exists()) {
            val bmp = BitmapFactory.decodeFile(layoutPath)
            if (bmp != null) ivLayout.setImageBitmap(bmp)
            tvLayoutStatus.text = "Uploaded — ${File(layoutPath).name}"
        } else {
            ivLayout.setImageResource(android.R.drawable.ic_menu_gallery)
            tvLayoutStatus.text = "Not uploaded"
        }
    }
}
