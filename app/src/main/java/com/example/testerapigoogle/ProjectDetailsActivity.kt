package com.example.testerapigoogle

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton

class ProjectDetailsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_project_details)
        supportActionBar?.hide()

        val flowMode = intent.getBooleanExtra("flow_mode", false)

        val etProjectName   = findViewById<EditText>(R.id.etProjectName)
        val etSiteLocation  = findViewById<EditText>(R.id.etSiteLocation)
        val etInspectorName = findViewById<EditText>(R.id.etInspectorName)

        findViewById<MaterialButton>(R.id.btnBackDetails).setOnClickListener { finish() }

        // Restore saved values
        val prefs = getSharedPreferences("google_api_prefs", MODE_PRIVATE)
        etProjectName.setText(prefs.getString("project_name", ""))
        etSiteLocation.setText(prefs.getString("site_location", ""))
        etInspectorName.setText(prefs.getString("inspector_name", ""))

        // Update save button label in flow mode
        val btnSave = findViewById<MaterialButton>(R.id.btnSaveDetails)
        if (flowMode) btnSave.text = "Next →  Documents"

        btnSave.setOnClickListener {
            val projectName = etProjectName.text?.toString()?.trim() ?: ""
            val siteLocation = etSiteLocation.text?.toString()?.trim() ?: ""
            val inspectorName = etInspectorName.text?.toString()?.trim() ?: ""

            if (projectName.isEmpty()) {
                etProjectName.error = "Project name is required"
                return@setOnClickListener
            }
            if (siteLocation.isEmpty()) {
                etSiteLocation.error = "Site location is required"
                return@setOnClickListener
            }
            if (inspectorName.isEmpty()) {
                etInspectorName.error = "Inspector name is required"
                return@setOnClickListener
            }

            prefs.edit()
                .putString("project_name", projectName)
                .putString("site_location", siteLocation)
                .putString("inspector_name", inspectorName)
                .apply()

            if (flowMode) {
                // Continue guided flow → Documents
                startActivity(Intent(this, DocumentsActivity::class.java).apply {
                    putExtra("flow_mode", true)
                })
            } else {
                Toast.makeText(this, "Details saved!", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        findViewById<MaterialButton>(R.id.btnClearDetails).setOnClickListener {
            etProjectName.text = null
            etSiteLocation.text = null
            etInspectorName.text = null
            Toast.makeText(this, "Fields cleared", Toast.LENGTH_SHORT).show()
        }
    }
}
