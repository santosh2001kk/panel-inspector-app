package com.example.testerapigoogle

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

class TaskSelectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_selection)

        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }

        val tasks = mapOf(
            R.id.cardCommissioning to "commissioning",
            R.id.cardMaintenance   to "maintenance",
            R.id.cardModification  to "modification",
            R.id.cardReplacement   to "replacement",
            R.id.cardTesting       to "testing",
            R.id.cardOthers        to "others"
        )

        tasks.forEach { (cardId, task) ->
            findViewById<MaterialCardView>(cardId).setOnClickListener {
                startActivity(Intent(this, ScanActivity::class.java).apply {
                    putExtra("task", task)
                })
            }
        }
    }
}
