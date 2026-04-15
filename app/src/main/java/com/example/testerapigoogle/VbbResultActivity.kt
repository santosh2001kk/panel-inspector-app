package com.example.testerapigoogle

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import java.io.File

class VbbResultActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_vbb_result)

        val imagePath     = intent.getStringExtra("panel_image_path")
        val mtzModel      = intent.getStringExtra("mtz_model")      ?: "Unknown"
        val ratedCurrent  = intent.getIntExtra("rated_current_A", 0)
        val vbbSide       = intent.getStringExtra("vbb_side")       ?: "unknown"
        val vbbWidthMm    = intent.getIntExtra("vbb_width_mm", 0)
        val busbarRating  = intent.getStringExtra("busbar_rating")  ?: ""
        val confidence    = intent.getStringExtra("confidence")     ?: "low"
        val notes         = intent.getStringExtra("notes")          ?: ""
        val safetyWarning = intent.getStringExtra("safety_warning") ?: ""
        val vbbBoxPx      = intent.getIntArrayExtra("vbb_box_px")

        // Load panel image
        val ivPanel = findViewById<android.widget.ImageView>(R.id.ivPanel)
        if (imagePath != null) {
            Glide.with(this).load(File(imagePath)).into(ivPanel)
        }

        // Draw VBB overlay box
        if (vbbBoxPx != null && vbbBoxPx.size >= 4 && imagePath != null) {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imagePath, opts)
            val overlay = findViewById<VbbOverlay>(R.id.vbbOverlay)
            overlay.post {
                overlay.setVbbBox(vbbBoxPx, opts.outWidth, opts.outHeight, vbbSide)
            }
        }

        // MTZ card
        findViewById<TextView>(R.id.tvMtzModel).text = mtzModel
        findViewById<TextView>(R.id.tvRating).text   = if (ratedCurrent > 0) "${ratedCurrent}A" else "N/A"
        findViewById<TextView>(R.id.tvConfidence).apply {
            text      = confidence.replaceFirstChar { it.uppercase() }
            setTextColor(when (confidence.lowercase()) {
                "high"   -> android.graphics.Color.parseColor("#3DCD58")
                "medium" -> android.graphics.Color.parseColor("#FFD600")
                else     -> android.graphics.Color.parseColor("#FF5252")
            })
        }

        // VBB details
        findViewById<TextView>(R.id.tvVbbSide).text = vbbSide.uppercase()
        findViewById<TextView>(R.id.tvVbbWidth).text = if (vbbWidthMm > 0) "${vbbWidthMm}mm" else "N/A"
        findViewById<TextView>(R.id.tvBusbarRating).text = busbarRating.ifBlank { "N/A" }

        // Safety warning
        findViewById<TextView>(R.id.tvSafety).text = safetyWarning.ifBlank {
            "VBB compartment contains live busbars at all times. Do NOT drill or penetrate."
        }

        // Notes
        findViewById<TextView>(R.id.tvNotes).text = notes

        // Back
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }
    }
}
