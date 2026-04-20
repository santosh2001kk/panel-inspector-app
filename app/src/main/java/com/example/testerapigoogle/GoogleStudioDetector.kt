package com.example.testerapigoogle

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
// Data models returned by the server
// ─────────────────────────────────────────────────────────────────────────────

/**
 * CubicleSegment
 *
 * Represents one physical cubicle section identified inside the panel.
 * Used when the server performs cubicle-level segmentation (e.g. to locate
 * the VBB cubicle in a PrismaSeT P panel or count sections in Okken).
 *
 * @param position  1-based index from left to right across the panel
 * @param type      "VBB" = vertical busbar cubicle (always live, never open),
 *                  "breaker" = normal breaker cubicle,
 *                  "cable" = cable entry cubicle
 * @param box       Bounding box in original image pixels [y1, x1, y2, x2]
 */
data class CubicleSegment(
    val position: Int,
    val type: String,
    val box: FloatArray,
)

/**
 * ApiResult
 *
 * The full result object returned after a successful call to detect().
 * All fields come directly from the server's JSON response.
 *
 * @param detections       List of all breakers found — each has a bounding box,
 *                         product type, circuit label, and current rating.
 * @param notes            One or two sentences from Gemini summarising what it saw.
 * @param safetyWarnings   List of safety warning strings (LOTO, arc flash, PPE, etc.)
 *                         Only populated when a work zone was drawn by the user.
 * @param panelType        Identified panel type: "PrismaSeT G", "PrismaSeT P", or "Okken".
 * @param panelSummary     One sentence explaining how Gemini identified the panel type.
 * @param qrCodes          Any QR code data strings decoded from the image.
 * @param busbarSide       Which side the VBB (busbar) compartment is on: "left", "right",
 *                         or "unknown". Only relevant for PrismaSeT P panels.
 * @param vbbBox           Pixel bounding box [y1, x1, y2, x2] of the VBB cubicle,
 *                         null if not detected.
 * @param cubicleCount     Total number of cubicles counted across the panel.
 * @param vbbPosition      1-based position of the VBB cubicle from the left.
 * @param cubicles         List of individual cubicle segments with their boxes.
 * @param cubicle_line     Text description of which cubicle the work zone falls in,
 *                         e.g. "Work zone is in cubicle 3 (breaker cubicle)".
 * @param catalogueGuidance Schneider catalogue checklist text injected for the detected
 *                         panel type and task (maintenance, commissioning, etc.).
 *                         Shown in the INSPECTION NOTES card on the result screen.
 *                         Empty when a work zone is drawn (safety warnings take over).
 */
data class ApiResult(
    val detections: List<Detection>,
    val notes: String,
    val safetyWarnings: List<String> = emptyList(),
    val panelType: String = "",
    val panelSummary: String = "",
    val qrCodes: List<String> = emptyList(),
    val busbarSide: String = "unknown",
    val vbbBox: FloatArray? = null,
    val cubicleCount: Int = 0,
    val vbbPosition: Int = 0,
    val cubicles: List<CubicleSegment> = emptyList(),
    val cubicle_line: String = "",
    val catalogueGuidance: String = "",
)

// ─────────────────────────────────────────────────────────────────────────────
// Network client
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GoogleStudioDetector
 *
 * Handles all communication between the Android app and the FastAPI server
 * running on the local network (10.114.64.176:8000).
 *
 * Flow:
 *   1. App takes a photo  →  ScanActivity saves it as a JPEG file
 *   2. WorkZoneActivity (or ScanActivity) calls detect() on a background thread
 *   3. detect() compresses + encodes the image to Base64, builds a JSON payload,
 *      and POSTs it to /api/analyze on the server
 *   4. The server sends the image to Google Gemini AI for analysis
 *   5. Gemini returns structured JSON: breakers, panel type, warnings, notes, etc.
 *   6. parseResponse() converts the JSON into an ApiResult object
 *   7. The activity receives the ApiResult and launches ResultActivity
 *
 * If the SLD (Single Line Diagram) or mechanical layout images are saved in
 * the Documents screen, they are also sent along so Gemini can cross-reference them.
 */
class GoogleStudioDetector {

    companion object {
        // Server URL — update this IP if the server moves to a different machine.
        // Port 8000 is the FastAPI server default.
        const val BASE_URL        = "http://10.114.64.176:8000/api/analyze"
        const val READ_LABEL_URL  = "http://10.114.64.176:8000/api/read_label"
        private const val TAG = "GoogleStudioDetector"
    }

    private val apiUrl = BASE_URL

    // OkHttp HTTP client with generous timeouts.
    // connectTimeout: how long to wait to establish a connection (30s)
    // readTimeout:    how long to wait for the server to respond (120s — Gemini can be slow)
    // writeTimeout:   how long to wait while sending the request (60s — images can be large)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * detect()
     *
     * Main entry point. Must be called from a background thread (Dispatchers.IO).
     * Sends the panel photo (and optional SLD/layout) to the server and returns
     * a fully parsed ApiResult.
     *
     * @param bitmap        The captured panel photo as a Bitmap.
     * @param workZone      Optional work zone rectangle drawn by the user (0-1000 coords).
     *                      When provided, Gemini focuses only on breakers inside this zone
     *                      and generates slide-based safety warnings.
     * @param safetyBuffer  A slightly expanded version of the work zone used as the
     *                      detection boundary. Also drawn in red on the result screen.
     * @param identifyOnly  If true, Gemini only identifies the panel type without
     *                      detecting individual breakers. Used in the quick scan flow.
     * @param busbarOnly    If true, runs the busbar/cubicle segmentation flow instead
     *                      of normal breaker detection. Used in LocateVbbActivity.
     * @param sldBitmap     Optional Single Line Diagram image from the Documents screen.
     *                      Sent to Gemini so it can cross-reference detected breakers
     *                      against the SLD circuit layout.
     * @param layoutBitmap  Optional mechanical layout image from the Documents screen.
     *                      Sent to Gemini for physical cubicle arrangement context.
     * @param task          The inspection task selected by the user:
     *                      "commissioning" | "maintenance" | "modification" |
     *                      "replacement" | "others"
     *                      Determines which catalogue checklist is returned.
     * @param username      Logged-in username (stored in login_prefs SharedPreferences).
     * @param projectName   Active project name (from google_api_prefs SharedPreferences).
     * @param site          Site location string.
     * @param inspector     Inspector name string.
     */
    fun detect(
        bitmap: Bitmap,
        workZone: ZoneCoords? = null,
        safetyBuffer: ZoneCoords? = null,
        identifyOnly: Boolean = false,
        busbarOnly: Boolean = false,
        sldBitmap: Bitmap? = null,
        layoutBitmap: Bitmap? = null,
        task: String = "others",
        username: String = "",
        projectName: String = "",
        site: String = "",
        inspector: String = ""
    ): ApiResult {

        // ── Step 1: Resize the photo to max 1536px on the longest side ────────
        // Gemini works best with images up to 1536px. Larger images are slower
        // and don't improve detection accuracy. Smaller images may miss small labels.
        val maxSide = 1536f
        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()
        val rs = if (origW > origH) maxSide / origW else maxSide / origH
        val resized = if (rs < 1f)
            Bitmap.createScaledBitmap(bitmap, (origW * rs).toInt(), (origH * rs).toInt(), true)
        else bitmap

        // ── Step 2: Compress to JPEG and encode as Base64 ─────────────────────
        // The server expects a Base64-encoded JPEG string, not a raw file upload.
        // Quality 90 keeps label text readable while keeping file size manageable.
        val baos = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        if (resized !== bitmap) resized.recycle()   // free memory if we created a new bitmap

        // Helper to convert any additional bitmap (SLD, layout) to Base64
        fun bitmapToB64(bmp: Bitmap): String {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }

        // ── Step 3: Build the JSON payload ────────────────────────────────────
        // All fields are optional on the server except imageBase64 and mimeType.
        val payload = JSONObject().apply {
            put("imageBase64", b64)          // the panel photo
            put("mimeType", "image/jpeg")
            put("identifyOnly", identifyOnly) // true = panel ID only, no breaker boxes
            put("busbarOnly", busbarOnly)     // true = cubicle segmentation mode
            put("task", task)                 // determines which checklist to return

            // Project metadata — used for server-side scan logging
            if (username.isNotEmpty())    put("username",    username)
            if (projectName.isNotEmpty()) put("projectName", projectName)
            if (site.isNotEmpty())        put("site",        site)
            if (inspector.isNotEmpty())   put("inspector",   inspector)

            // Reference documents — only sent if the user uploaded them
            sldBitmap?.let    { put("sldBase64",    bitmapToB64(it)) }
            layoutBitmap?.let { put("layoutBase64", bitmapToB64(it)) }

            // Work zone + safety buffer — only present when user drew a zone
            // Coordinates are normalised 0-1000 (not pixels).
            workZone?.let { wz ->
                put("workZone", JSONObject().apply {
                    put("ymin", wz.ymin); put("xmin", wz.xmin)
                    put("ymax", wz.ymax); put("xmax", wz.xmax)
                })
            }
            safetyBuffer?.let { sb ->
                put("safetyBuffer", JSONObject().apply {
                    put("ymin", sb.ymin); put("xmin", sb.xmin)
                    put("ymax", sb.ymax); put("xmax", sb.xmax)
                })
            }
        }

        // ── Step 4: Send the HTTP POST request ────────────────────────────────
        val request = Request.Builder()
            .url(apiUrl)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = client.newCall(request).execute()
            val body     = response.body?.string() ?: return ApiResult(emptyList(), "Empty response")

            if (!response.isSuccessful) {
                Log.e(TAG, "API error ${response.code}: $body")
                return ApiResult(emptyList(), "API error: ${response.code}")
            }

            Log.d(TAG, "Response: $body")
            // Pass the original (pre-resize) dimensions and scale factor so
            // bounding boxes are converted back to original pixel coordinates.
            parseResponse(body, origW, origH, if (rs < 1f) rs else 1f)

        } catch (e: Exception) {
            Log.e(TAG, "Network error: ${e.message}")
            ApiResult(emptyList(), "Network error: ${e.message}")
        }
    }

    /**
     * parseResponse()
     *
     * Converts the raw JSON string from the server into a structured ApiResult.
     *
     * Important — coordinate conversion:
     *   The server returns all bounding boxes in pixel coordinates based on the
     *   RESIZED image (max 1536px). This function scales them back up to match
     *   the ORIGINAL image dimensions using the resizeScale factor.
     *
     *   sx = 1 / resizeScale  →  multiplying by sx converts resized-px → original-px
     *
     * @param json         Raw JSON response string from the server.
     * @param origW        Original image width in pixels (before resize).
     * @param origH        Original image height in pixels (before resize).
     * @param resizeScale  The scale factor that was applied when resizing
     *                     (e.g. 0.75 means the image was shrunk to 75%).
     *                     Pass 1.0 if no resize was done.
     */
    private fun parseResponse(json: String, origW: Float, origH: Float, resizeScale: Float): ApiResult {
        val detections     = mutableListOf<Detection>()
        val safetyWarnings = mutableListOf<String>()
        var notes          = ""
        var panelType      = ""
        var panelSummary   = ""
        var busbarSide     = "unknown"
        var vbbBox: FloatArray? = null
        var cubicleCount   = 0
        var vbbPosition    = 0
        var cubicle_line       = ""
        var catalogueGuidance  = ""
        val cubicles           = mutableListOf<CubicleSegment>()
        val qrCodes        = mutableListOf<String>()

        try {
            val root = JSONObject(json)

            // ── Top-level fields ──────────────────────────────────────────────
            notes             = root.optString("notes", "")
            panelType         = root.optString("panel_type", "")
            panelSummary      = root.optString("panel_summary", "")
            busbarSide        = root.optString("busbar_side", "unknown")
            cubicleCount      = root.optInt("cubicle_count", 0)
            vbbPosition       = root.optInt("vbb_position", 0)
            cubicle_line      = root.optString("cubicle_line", "")
            catalogueGuidance = root.optString("catalogue_guidance", "")

            // Scale factor to convert resized-pixel coords → original-pixel coords.
            // If the image was not resized (scale = 1.0), sx = 1.0 (no change).
            val sx = if (resizeScale < 1f) 1f / resizeScale else 1f

            // ── VBB bounding box ──────────────────────────────────────────────
            // The VBB (Vertical Busbar Box) cubicle box in pixel coords [y1,x1,y2,x2].
            // Used in ResultActivity to show a warning if the user's work zone
            // overlaps with the live VBB cubicle.
            root.optJSONArray("vbb_box")?.let { arr ->
                if (arr.length() >= 4) {
                    val ymin = arr.getDouble(0).toFloat()
                    val xmin = arr.getDouble(1).toFloat()
                    val ymax = arr.getDouble(2).toFloat()
                    val xmax = arr.getDouble(3).toFloat()
                    vbbBox = floatArrayOf(
                        (ymin * sx).coerceIn(0f, origH),
                        (xmin * sx).coerceIn(0f, origW),
                        (ymax * sx).coerceIn(0f, origH),
                        (xmax * sx).coerceIn(0f, origW)
                    )
                }
            }

            // ── Cubicle segments ──────────────────────────────────────────────
            // Each cubicle has a position (1 = leftmost), a type ("vbb"/"breaker"/"cable"),
            // and a bounding box. Drawn as coloured boxes in BoundingBoxOverlay.
            root.optJSONArray("cubicles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c   = arr.getJSONObject(i)
                    val box = c.optJSONArray("box") ?: continue
                    if (box.length() < 4) continue
                    cubicles.add(CubicleSegment(
                        position = c.optInt("position", i + 1),
                        type     = c.optString("type", "breaker"),
                        box      = floatArrayOf(
                            (box.getDouble(0).toFloat() * sx).coerceIn(0f, origH),
                            (box.getDouble(1).toFloat() * sx).coerceIn(0f, origW),
                            (box.getDouble(2).toFloat() * sx).coerceIn(0f, origH),
                            (box.getDouble(3).toFloat() * sx).coerceIn(0f, origW)
                        )
                    ))
                }
            }

            // ── Safety warnings ───────────────────────────────────────────────
            // Each string is a full warning message shown in the red SAFETY WARNINGS
            // card on the result screen. Only present when a work zone was drawn.
            val warnings = root.optJSONArray("safety_warnings")
            if (warnings != null) {
                for (i in 0 until warnings.length()) safetyWarnings.add(warnings.getString(i))
            }

            // ── QR codes ──────────────────────────────────────────────────────
            // Any QR code data strings decoded from the image by the server.
            // Shown via the QR button on the result screen if non-empty.
            val qrArray = root.optJSONArray("qr_codes")
            if (qrArray != null) {
                for (i in 0 until qrArray.length()) qrCodes.add(qrArray.getString(i))
            }

            // ── Breaker detections ────────────────────────────────────────────
            // Each breaker has:
            //   type          — Schneider product name (e.g. "Compact NSX")
            //   box           — [ymin, xmin, ymax, xmax] in resized-image pixels
            //   circuit_label — text on label strip (e.g. "LV MAIN") — may be empty
            //   rating        — rated current (e.g. "400A") — may be empty
            //
            // Coordinates are multiplied by sx to get back to original image pixels,
            // then clamped so they never go outside the image boundary.
            val breakers = root.optJSONArray("breakers")
            if (breakers != null) {
                for (i in 0 until breakers.length()) {
                    val b    = breakers.getJSONObject(i)
                    val type = b.optString("type", "UNKNOWN").uppercase()
                    val box  = b.optJSONArray("box") ?: continue
                    if (box.length() < 4) continue

                    val ymin = box.getDouble(0).toFloat()
                    val xmin = box.getDouble(1).toFloat()
                    val ymax = box.getDouble(2).toFloat()
                    val xmax = box.getDouble(3).toFloat()

                    // Convert from resized-pixel coords back to original-pixel coords
                    val x1 = (xmin * sx).coerceIn(0f, origW)
                    val y1 = (ymin * sx).coerceIn(0f, origH)
                    val x2 = (xmax * sx).coerceIn(0f, origW)
                    val y2 = (ymax * sx).coerceIn(0f, origH)

                    val circuitLabel = b.optString("circuit_label", "")
                    val rating       = b.optString("rating", "")

                    detections.add(Detection(type, 1.0f, x1, y1, x2, y2, circuitLabel, rating))
                    Log.d(TAG, "$type [$x1,$y1,$x2,$y2] label='$circuitLabel' rating='$rating'")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }

        Log.d(TAG, "Total: ${detections.size} detections, ${safetyWarnings.size} warnings, panel: $panelType, busbar: $busbarSide")
        return ApiResult(
            detections, notes, safetyWarnings, panelType, panelSummary,
            qrCodes, busbarSide, vbbBox, cubicleCount, vbbPosition,
            cubicles, cubicle_line, catalogueGuidance
        )
    }

    /**
     * readLabel()
     *
     * Tap-to-read fallback: sends a small cropped image of a single breaker
     * to the /api/read_label endpoint.  Gemini focuses entirely on reading
     * the text at close range — better accuracy than the full-panel scan.
     *
     * Must be called from a background thread (Dispatchers.IO).
     *
     * @param bitmap  Cropped bitmap of just the breaker area (with some padding).
     * @return Pair<circuitLabel, rating> — either or both may be empty strings.
     */
    fun readLabel(bitmap: Bitmap): Pair<String, String> {
        // Compress the cropped region to JPEG at high quality (95) so small
        // label text survives the encoding without too many compression artefacts.
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        val payload = JSONObject().apply {
            put("imageBase64", b64)
            put("mimeType", "image/jpeg")
        }

        val request = Request.Builder()
            .url(READ_LABEL_URL)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return Pair("", "")
            Log.d(TAG, "readLabel response: $body")
            val json = JSONObject(body)
            Pair(json.optString("circuit_label", ""), json.optString("rating", ""))
        } catch (e: Exception) {
            Log.e(TAG, "readLabel error: ${e.message}")
            Pair("", "")
        }
    }
}
