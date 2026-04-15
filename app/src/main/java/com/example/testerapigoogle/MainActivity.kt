package com.example.testerapigoogle

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private var pulseAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.btnLogout).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Sign Out")
                .setMessage("Are you sure you want to sign out?")
                .setPositiveButton("Sign Out") { _, _ ->
                    getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("is_logged_in", false).apply()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<MaterialCardView>(R.id.cardScan)
            .setOnClickListener { startActivity(Intent(this, ProjectDetailsActivity::class.java).apply {
                putExtra("flow_mode", true)  // guided flow — not standalone
            }) }

        findViewById<MaterialCardView>(R.id.cardReports)
            .setOnClickListener { startActivity(Intent(this, ReportsActivity::class.java)) }

        findViewById<MaterialCardView>(R.id.cardPanelRegister)
            .setOnClickListener { startActivity(Intent(this, PanelRegisterActivity::class.java)) }

        findViewById<MaterialCardView>(R.id.cardProjectDetails)
            .setOnClickListener { startActivity(Intent(this, ProjectDetailsActivity::class.java)) }

        findViewById<MaterialCardView>(R.id.cardDocuments)
            .setOnClickListener { startActivity(Intent(this, DocumentsActivity::class.java)) }

        // Make metrics clickable
        findViewById<LinearLayout>(R.id.tileScans).setOnClickListener {
            startActivity(Intent(this, ReportsActivity::class.java))
        }
        
        findViewById<LinearLayout>(R.id.tileWarnings).setOnClickListener {
            showWarningsSummary()
        }

        startPulseAnimation()
        checkModelStatus()
    }

    private fun startPulseAnimation() {
        val ivPulse = findViewById<ImageView>(R.id.ivPulse)
        pulseAnimator = ObjectAnimator.ofFloat(ivPulse, "alpha", 1f, 0.2f).apply {
            duration = 1000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun checkModelStatus() {
        val tvStatus = findViewById<TextView>(R.id.tvModelStatus)
        val ivPulse = findViewById<ImageView>(R.id.ivPulse)
        
        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                val isOnline = isServerReachable("10.222.37.176", 8000)
                withContext(Dispatchers.Main) {
                    if (isOnline) {
                        tvStatus.text = "Model ready"
                        ivPulse.setColorFilter(getColor(R.color.white))
                    } else {
                        tvStatus.text = "Server offline"
                        ivPulse.setColorFilter(getColor(android.R.color.holo_orange_light))
                    }
                }
                delay(10000) // Check every 10 seconds
            }
        }
    }

    private fun isServerReachable(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2000)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun showWarningsSummary() {
        val prefs = getSharedPreferences("google_api_prefs", MODE_PRIVATE)
        val warningCount = prefs.getInt("warning_count", 0)
        
        AlertDialog.Builder(this)
            .setTitle("Safety Summary")
            .setMessage("You have encountered $warningCount safety warnings in your recent inspections.\n\nAlways ensure proper PPE is worn when working near detected busbars.")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        val prefs        = getSharedPreferences("google_api_prefs", MODE_PRIVATE)
        val scanCount    = prefs.getInt("scan_count", 0)
        val breakerCount = prefs.getInt("warning_count", 0)
        val lastTimeMs   = prefs.getLong("last_scan_time_ms", 0L)
        val lastPanel    = prefs.getString("last_panel_type", "") ?: ""
        val projectName  = prefs.getString("project_name", "") ?: ""

        animateCount(findViewById(R.id.tvScanCount), scanCount)
        animateCount(findViewById(R.id.tvBreakerCount), breakerCount)

        val tvLastPanel = findViewById<TextView>(R.id.tvLastPanelType)
        val tvLastScan  = findViewById<TextView>(R.id.tvLastScan)
        val tvProjectSubtitle = findViewById<TextView>(R.id.tvProjectSubtitle)

        if (lastPanel.isNotBlank()) {
            tvLastPanel.text = lastPanel
            tvLastScan.text  = timeAgo(lastTimeMs)
        } else {
            tvLastPanel.text = "No scans yet"
            tvLastScan.text  = ""
        }

        if (projectName.isNotBlank()) {
            tvProjectSubtitle.text = "Active: $projectName"
            tvProjectSubtitle.setTextColor(getColor(R.color.schneider_green))
        } else {
            tvProjectSubtitle.text = "Enter project, site and inspector info"
            tvProjectSubtitle.setTextColor(0xB3FFFFFF.toInt())
        }

        val tvDocsSub = findViewById<TextView>(R.id.tvDocumentsSubtitle)
        val hasSld    = prefs.getString("sld_image_path", null) != null
        val hasLayout = prefs.getString("layout_image_path", null) != null
        tvDocsSub.text = when {
            hasSld && hasLayout -> "SLD + Layout uploaded"
            hasSld              -> "SLD uploaded"
            hasLayout           -> "Layout uploaded"
            else                -> "Upload SLD and mechanical layout"
        }
        tvDocsSub.setTextColor(if (hasSld || hasLayout) getColor(R.color.schneider_green) else 0xB3FFFFFF.toInt())
    }

    private fun animateCount(tv: TextView, target: Int) {
        val animator = ValueAnimator.ofInt(0, target)
        animator.duration = 800L
        animator.addUpdateListener { tv.text = it.animatedValue.toString() }
        animator.start()
    }

    private fun timeAgo(timeMs: Long): String {
        if (timeMs == 0L) return ""
        val diff = System.currentTimeMillis() - timeMs
        val mins  = diff / 60_000
        val hours = diff / 3_600_000
        val days  = diff / 86_400_000
        return when {
            mins  < 1  -> "Just now"
            mins  < 60 -> "${mins}m ago"
            hours < 24 -> "${hours}h ago"
            days  == 1L -> "Yesterday"
            else        -> "${days}d ago"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
    }
}
