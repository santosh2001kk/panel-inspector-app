package com.example.testerapigoogle

import java.io.Serializable

data class ZoneCoords(
    val ymin: Int,
    val xmin: Int,
    val ymax: Int,
    val xmax: Int
) : Serializable
