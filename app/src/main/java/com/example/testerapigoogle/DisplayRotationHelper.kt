package com.example.testerapigoogle

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Surface
import com.google.ar.core.Session

/**
 * Tracks display rotation and updates the ARCore session geometry when it changes.
 */
class DisplayRotationHelper(private val context: Context) {

    private var viewportChanged = false
    private var viewportWidth   = 0
    private var viewportHeight  = 0

    private val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display
    } else {
        @Suppress("DEPRECATION")
        (context as Activity).windowManager.defaultDisplay
    }

    fun onResume()  { viewportChanged = true }
    fun onPause()   {}

    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth  = width
        viewportHeight = height
        viewportChanged = true
    }

    fun updateSessionIfNeeded(session: Session) {
        if (viewportChanged) {
            val rotation = display?.rotation ?: Surface.ROTATION_0
            session.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
            viewportChanged = false
        }
    }
}
