package com.example.volumeguard

import android.content.Context
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager

/** Temporarily locks the device rotation to portrait during the coordinate-based recovery. */
object OrientationControl {
    fun forcePortraitIfNeeded(context: Context): Boolean {
        if (!Prefs.forcePortraitDuringFallback(context)) return false
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        // Em retrato não é necessário pedir permissão especial nem alterar a rotação.
        if (metrics.widthPixels <= metrics.heightPixels) return true
        if (!Settings.System.canWrite(context)) return false

        return try {
            val resolver = context.contentResolver
            val oldAccelerometer = Settings.System.getInt(
                resolver, Settings.System.ACCELEROMETER_ROTATION, 1
            )
            val oldUserRotation = Settings.System.getInt(
                resolver, Settings.System.USER_ROTATION, Surface.ROTATION_0
            )
            if (!Prefs.orientationOverrideActive(context)) {
                Prefs.saveOriginalOrientation(context, oldAccelerometer, oldUserRotation)
            }
            Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(resolver, Settings.System.USER_ROTATION, Surface.ROTATION_0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun restore(context: Context) {
        if (!Prefs.orientationOverrideActive(context)) return
        try {
            if (!Settings.System.canWrite(context)) return
            val resolver = context.contentResolver
            Settings.System.putInt(
                resolver,
                Settings.System.USER_ROTATION,
                Prefs.originalUserRotation(context)
            )
            Settings.System.putInt(
                resolver,
                Settings.System.ACCELEROMETER_ROTATION,
                Prefs.originalAccelerometerRotation(context)
            )
            Prefs.clearOrientationOverride(context)
        } catch (_: Exception) {
            // Keep the saved state so a later service start can retry restoration.
        }
    }
}
