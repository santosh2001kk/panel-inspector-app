package com.example.testerapigoogle

data class Detection(
    val label: String,
    val score: Float,   // always 1.0f from this API (no confidence returned)
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
)
