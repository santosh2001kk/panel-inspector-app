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

data class CubicleSegment(
    val position: Int,
    val type: String,       // "VBB" or "breaker"
    val box: FloatArray,    // [y1, x1, y2, x2] pixel coords
)

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

class GoogleStudioDetector {

    companion object {
        const val BASE_URL = "http://10.222.37.176:8000/api/analyze"
        private const val TAG = "GoogleStudioDetector"
    }

    private val apiUrl = BASE_URL

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Call from Dispatchers.IO
    fun detect(bitmap: Bitmap, workZone: ZoneCoords? = null, safetyBuffer: ZoneCoords? = null, identifyOnly: Boolean = false, busbarOnly: Boolean = false, sldBitmap: Bitmap? = null, layoutBitmap: Bitmap? = null, task: String = "others"): ApiResult {
        // Resize to max 1536 for better bounding box accuracy
        val maxSide = 1536f
        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()
        val rs = if (origW > origH) maxSide / origW else maxSide / origH
        val resized = if (rs < 1f)
            Bitmap.createScaledBitmap(bitmap, (origW * rs).toInt(), (origH * rs).toInt(), true)
        else bitmap

        val baos = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        if (resized !== bitmap) resized.recycle()

        fun bitmapToB64(bmp: Bitmap): String {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }

        val payload = JSONObject().apply {
            put("imageBase64", b64)
            put("mimeType", "image/jpeg")
            put("identifyOnly", identifyOnly)
            put("busbarOnly", busbarOnly)
            put("task", task)
            sldBitmap?.let { put("sldBase64", bitmapToB64(it)) }
            layoutBitmap?.let { put("layoutBase64", bitmapToB64(it)) }
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
            parseResponse(body, origW, origH, if (rs < 1f) rs else 1f)
        } catch (e: Exception) {
            Log.e(TAG, "Network error: ${e.message}")
            ApiResult(emptyList(), "Network error: ${e.message}")
        }
    }

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
            val root     = JSONObject(json)
            notes        = root.optString("notes", "")
            panelType    = root.optString("panel_type", "")
            panelSummary = root.optString("panel_summary", "")
            busbarSide   = root.optString("busbar_side", "unknown")
            cubicleCount = root.optInt("cubicle_count", 0)
            vbbPosition  = root.optInt("vbb_position", 0)
            cubicle_line      = root.optString("cubicle_line", "")
            catalogueGuidance = root.optString("catalogue_guidance", "")
            
            val sx = if (resizeScale < 1f) 1f / resizeScale else 1f

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

            // Parse cubicle segments
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

            // Parse safety warnings
            val warnings = root.optJSONArray("safety_warnings")
            if (warnings != null) {
                for (i in 0 until warnings.length()) safetyWarnings.add(warnings.getString(i))
            }

            val qrArray = root.optJSONArray("qr_codes")
            if (qrArray != null) {
                for (i in 0 until qrArray.length()) qrCodes.add(qrArray.getString(i))
            }

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

                    val x1 = (xmin * sx).coerceIn(0f, origW)
                    val y1 = (ymin * sx).coerceIn(0f, origH)
                    val x2 = (xmax * sx).coerceIn(0f, origW)
                    val y2 = (ymax * sx).coerceIn(0f, origH)

                    detections.add(Detection(type, 1.0f, x1, y1, x2, y2))
                    Log.d(TAG, "$type [$x1,$y1,$x2,$y2]")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
        Log.d(TAG, "Total: ${detections.size} detections, ${safetyWarnings.size} warnings, panel: $panelType, busbar: $busbarSide")
        return ApiResult(detections, notes, safetyWarnings, panelType, panelSummary, qrCodes, busbarSide, vbbBox, cubicleCount, vbbPosition, cubicles, cubicle_line, catalogueGuidance)
    }
}
