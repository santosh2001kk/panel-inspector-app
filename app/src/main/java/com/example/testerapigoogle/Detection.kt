package com.example.testerapigoogle

/**
 * Detection
 *
 * Represents a single circuit breaker detected by Gemini AI in a panel photo.
 * One Detection object is created for every individual breaker found.
 *
 * Coordinate system:
 *   x1, y1 = top-left corner of the bounding box  (in original image pixels)
 *   x2, y2 = bottom-right corner of the bounding box (in original image pixels)
 *
 * These pixel coordinates are drawn directly onto the BoundingBoxOverlay
 * that sits on top of the result image.
 *
 * Example:
 *   Detection(
 *     label        = "Compact NSX",
 *     score        = 1.0f,
 *     x1 = 120f, y1 = 300f, x2 = 280f, y2 = 520f,
 *     circuitLabel = "LV MAIN",
 *     rating       = "400A"
 *   )
 */
data class Detection(

    // Product name of the breaker as identified by Gemini.
    // Always one of: "MasterPact MTZ", "MasterPact NT", "Compact NSX",
    // "Compact NS", "Acti9", "iC60", "Multi9", or "UNKNOWN".
    val label: String,

    // Confidence score — always 1.0f because Gemini does not return a
    // confidence value. Kept for future compatibility.
    val score: Float,

    // Bounding box coordinates in original image pixels (before any scaling).
    // x1/y1 = top-left, x2/y2 = bottom-right.
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,

    // Circuit name read from the label strip next to or on the breaker face.
    // e.g. "LV MAIN", "DIST-1", "UPS FEEDER", "LIGHTING DB".
    // Empty string ("") if not visible or not readable in the photo.
    val circuitLabel: String = "",

    // Rated current read from the breaker face nameplate.
    // e.g. "400A", "250A", "63A", "16A".
    // Empty string ("") if not visible or not readable in the photo.
    val rating: String = "",
)
