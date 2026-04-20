package com.example.testerapigoogle

import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import com.google.android.material.button.MaterialButton
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * AR Panel Verification Screen
 *
 * Uses ARCore Augmented Image Tracking to verify the worker is pointing their
 * camera at the EXACT same panel that was scanned during the risk analysis.
 *
 * No cloud, no API key required — works fully offline on the same device.
 */
class ArVerifyActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView:          GLSurfaceView
    private lateinit var tvStatus:             TextView
    private lateinit var tvInstruction:        TextView

    private var session:               Session? = null
    private var backgroundRenderer:    ArBackgroundRenderer? = null
    private var displayRotationHelper: DisplayRotationHelper? = null

    private var panelDetected     = false
    private var installRequested  = false
    private var refImageAvailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_verify)

        surfaceView   = findViewById(R.id.arSurfaceView)
        tvStatus      = findViewById(R.id.tvArStatus)
        tvInstruction = findViewById(R.id.tvArInstruction)

        displayRotationHelper = DisplayRotationHelper(this)

        surfaceView.apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@ArVerifyActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        findViewById<MaterialButton>(R.id.btnArClose).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()

        // 1. Check ARCore is installed
        when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                installRequested = true
                return
            }
            ArCoreApk.InstallStatus.INSTALLED -> Unit
        }

        // 2. Create session
        if (session == null) {
            try {
                session = Session(this)
                configureSession(session!!)
            } catch (e: UnavailableArcoreNotInstalledException) {
                showError("ARCore is not installed on this device.")
                return
            } catch (e: UnavailableDeviceNotCompatibleException) {
                showError("This device does not support ARCore.")
                return
            } catch (e: Exception) {
                showError("Could not start AR session: ${e.message}")
                return
            }
        }

        // 3. Resume session
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            showError("Camera is not available. Try closing other apps.")
            session = null
            return
        }

        surfaceView.onResume()
        displayRotationHelper?.onResume()

        if (refImageAvailable) {
            runOnUiThread {
                tvInstruction.text = "Point the camera slowly at the panel until it is recognised."
                tvStatus.text      = "🔍 Searching for panel..."
                tvStatus.setBackgroundColor(0xCC1565C0.toInt())
            }
        } else {
            runOnUiThread {
                tvInstruction.text = "No reference image found. Please re-scan the panel first."
                tvStatus.text      = "⚠️ No reference image"
                tvStatus.setBackgroundColor(0xCCE65100.toInt())
            }
        }
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        displayRotationHelper?.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
    }

    // ── Session configuration ────────────────────────────────────────────────

    private fun configureSession(s: Session) {
        val config = Config(s).apply {
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            focusMode  = Config.FocusMode.AUTO
        }

        // Load reference image — the panel photo from the scan
        val refPath = intent.getStringExtra("reference_image_path")
        if (refPath != null) {
            val bmp = BitmapFactory.decodeFile(refPath)
            if (bmp != null) {
                try {
                    val db = AugmentedImageDatabase(s)
                    db.addImage("panel", bmp)
                    config.augmentedImageDatabase = db
                    refImageAvailable = true
                } catch (e: Exception) {
                    // Image doesn't have enough features for tracking
                    // (e.g. too blurry or too flat) — gracefully degrade
                }
            }
        }

        s.configure(config)
    }

    // ── GL Renderer ──────────────────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer = ArBackgroundRenderer().also { it.createOnGlThread() }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper?.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val s   = session            ?: return
        val bgr = backgroundRenderer ?: return

        displayRotationHelper?.updateSessionIfNeeded(s)

        try {
            s.setCameraTextureName(bgr.textureId)
            val frame = s.update()

            // Draw camera feed
            bgr.draw(frame)

            // Check augmented image tracking state
            for (img in frame.getUpdatedTrackables(AugmentedImage::class.java)) {
                when (img.trackingState) {
                    TrackingState.TRACKING -> {
                        if (!panelDetected) {
                            panelDetected = true
                            runOnUiThread { onPanelFound() }
                        }
                    }
                    TrackingState.PAUSED,
                    TrackingState.STOPPED -> {
                        if (panelDetected) {
                            panelDetected = false
                            runOnUiThread { onPanelLost() }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Non-fatal — session may not be ready yet
        }
    }

    // ── UI feedback ──────────────────────────────────────────────────────────

    private fun onPanelFound() {
        tvStatus.text = "✅ Correct Panel Verified — Safe to proceed!"
        tvStatus.setBackgroundColor(0xCC1B5E20.toInt())
        tvInstruction.text = "You are at the correct panel. Work zones are confirmed."
    }

    private fun onPanelLost() {
        tvStatus.text = "🔍 Searching for panel..."
        tvStatus.setBackgroundColor(0xCC1565C0.toInt())
        tvInstruction.text = "Point the camera slowly at the panel until it is recognised."
    }

    private fun showError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
