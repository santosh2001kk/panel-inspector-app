package com.example.testerapigoogle

import java.util.UUID

data class PanelIntervention(
    val dateMs: Long,
    val task: String,
    val inspector: String,
    val panelType: String,
    val busbarSide: String,
    val notes: String,
    val warnings: List<String>,
    val imagePath: String
)

data class PanelAsset(
    val id: String = UUID.randomUUID().toString(),
    val panelId: String,                    // e.g. "DB-01" — given by worker
    val site: String,                       // from project details
    val firstSeenMs: Long,                  // date of first scan
    val firstTask: String,                  // commissioning / maintenance etc.
    val firstInspector: String,
    val panelType: String,                  // PrismaSeT P / G / Okken
    val busbarSide: String,                 // left / right / unknown
    val interventions: List<PanelIntervention> = emptyList()  // full history
)
