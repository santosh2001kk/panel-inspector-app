package com.example.testerapigoogle

import java.util.UUID

data class ScanRecord(
    val id: String = UUID.randomUUID().toString(),
    val dateMs: Long,
    val projectName: String,
    val siteLocation: String,
    val inspectorName: String,
    val panelType: String,
    val panelSummary: String,
    val notes: String,
    val warnings: List<String>,
    val imagePath: String,
    val reportFilePath: String,
    val busbarOnly: Boolean = false,
    val cubicleCount: Int = 0,
    val task: String? = "others"        // commissioning | maintenance | modification | replacement | others
)
