package com.example.volumeguard

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object Prefs {
    const val TARGET_NONE = "none"
    const val TARGET_SLIDER = "slider"
    const val TARGET_OK = "ok"

    private const val FILE = "volume_guard_settings"
    private const val RUNNING = "running"
    private const val VOLUME = "volume_percent"
    private const val VOLUME_INTERVAL = "volume_interval_seconds"
    private const val SCREEN_ENABLED = "screen_lock_enabled"
    private const val SCREEN_INTERVAL = "screen_interval_seconds"
    private const val START_BOOT = "start_on_boot"

    private const val UI_FALLBACK_ENABLED = "ui_fallback_enabled"
    private const val SLIDER_X = "fallback_slider_x"
    private const val SLIDER_Y = "fallback_slider_y"
    private const val OK_X = "fallback_ok_x"
    private const val OK_Y = "fallback_ok_y"
    private const val CALIBRATION_TARGET = "calibration_target"
    private const val CALIBRATION_ACTIVE = "calibration_active"
    private const val FALLBACK_IN_PROGRESS = "fallback_in_progress"
    private const val FALLBACK_STARTED_AT = "fallback_started_at"
    private const val LAST_FALLBACK_ATTEMPT = "last_fallback_attempt"
    private const val FALLBACK_STEP_DELAY_SECONDS = "fallback_step_delay_seconds"
    private const val FALLBACK_SCREEN_WAKE_AT = "fallback_screen_wake_at"

    private fun p(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun running(c: Context) = p(c).getBoolean(RUNNING, false)
    fun setRunning(c: Context, v: Boolean) = p(c).edit().putBoolean(RUNNING, v).apply()

    fun volume(c: Context) = p(c).getInt(VOLUME, 70).coerceIn(0, 100)
    fun setVolume(c: Context, v: Int) = p(c).edit().putInt(VOLUME, v.coerceIn(0, 100)).apply()

    fun volumeInterval(c: Context) = p(c).getInt(VOLUME_INTERVAL, 60).coerceIn(5, 86400)
    fun setVolumeInterval(c: Context, v: Int) = p(c).edit().putInt(VOLUME_INTERVAL, v.coerceIn(5, 86400)).apply()

    fun screenEnabled(c: Context) = p(c).getBoolean(SCREEN_ENABLED, false)
    fun setScreenEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean(SCREEN_ENABLED, v).apply()

    fun screenInterval(c: Context) = p(c).getInt(SCREEN_INTERVAL, 15).coerceIn(5, 3600)
    fun setScreenInterval(c: Context, v: Int) = p(c).edit().putInt(SCREEN_INTERVAL, v.coerceIn(5, 3600)).apply()

    fun startOnBoot(c: Context) = p(c).getBoolean(START_BOOT, false)
    fun setStartOnBoot(c: Context, v: Boolean) = p(c).edit().putBoolean(START_BOOT, v).apply()

    fun uiFallbackEnabled(c: Context) = p(c).getBoolean(UI_FALLBACK_ENABLED, false)
    fun setUiFallbackEnabled(c: Context, v: Boolean) =
        p(c).edit().putBoolean(UI_FALLBACK_ENABLED, v).apply()

    fun calibrationTarget(c: Context) = p(c).getString(CALIBRATION_TARGET, TARGET_NONE) ?: TARGET_NONE
    fun setCalibrationTarget(c: Context, target: String) =
        p(c).edit().putString(CALIBRATION_TARGET, target).apply()

    fun calibrationActive(c: Context) = p(c).getBoolean(CALIBRATION_ACTIVE, false)
    fun setCalibrationActive(c: Context, active: Boolean) =
        p(c).edit().putBoolean(CALIBRATION_ACTIVE, active).apply()

    fun fallbackInProgress(c: Context) = p(c).getBoolean(FALLBACK_IN_PROGRESS, false)
    fun setFallbackInProgress(c: Context, active: Boolean) =
        p(c).edit().putBoolean(FALLBACK_IN_PROGRESS, active).apply()

    fun fallbackStartedAt(c: Context) = p(c).getLong(FALLBACK_STARTED_AT, 0L)
    fun setFallbackStartedAt(c: Context, at: Long) =
        p(c).edit().putLong(FALLBACK_STARTED_AT, at).apply()

    fun lastFallbackAttempt(c: Context) = p(c).getLong(LAST_FALLBACK_ATTEMPT, 0L)
    fun setLastFallbackAttempt(c: Context, at: Long) =
        p(c).edit().putLong(LAST_FALLBACK_ATTEMPT, at).apply()

    /** Delay shared by each stage of the optional screen-based recovery (default: 2 seconds). */
    fun fallbackStepDelaySeconds(c: Context) =
        p(c).getInt(FALLBACK_STEP_DELAY_SECONDS, 2).coerceIn(1, 10)

    fun setFallbackStepDelaySeconds(c: Context, seconds: Int) =
        p(c).edit().putInt(FALLBACK_STEP_DELAY_SECONDS, seconds.coerceIn(1, 10)).apply()

    fun fallbackScreenWakeAt(c: Context) = p(c).getLong(FALLBACK_SCREEN_WAKE_AT, 0L)
    fun setFallbackScreenWakeAt(c: Context, at: Long) =
        p(c).edit().putLong(FALLBACK_SCREEN_WAKE_AT, at).apply()

    fun setPosition(c: Context, target: String, x: Float, y: Float) {
        val safeX = x.coerceIn(0f, 1f)
        val safeY = y.coerceIn(0f, 1f)
        val e = p(c).edit()
        when (target) {
            TARGET_SLIDER -> e.putFloat(SLIDER_X, safeX).putFloat(SLIDER_Y, safeY)
            TARGET_OK -> e.putFloat(OK_X, safeX).putFloat(OK_Y, safeY)
        }
        e.apply()
    }

    fun position(c: Context, target: String): Pair<Float, Float>? {
        val prefs = p(c)
        val x: Float
        val y: Float
        when (target) {
            TARGET_SLIDER -> {
                x = prefs.getFloat(SLIDER_X, -1f)
                y = prefs.getFloat(SLIDER_Y, -1f)
            }
            TARGET_OK -> {
                x = prefs.getFloat(OK_X, -1f)
                y = prefs.getFloat(OK_Y, -1f)
            }
            else -> return null
        }
        if (x !in 0f..1f || y !in 0f..1f) return null
        return Pair(x, y)
    }

    fun hasSliderPosition(c: Context) = position(c, TARGET_SLIDER) != null
    fun hasOkPosition(c: Context) = position(c, TARGET_OK) != null

    fun accessibilityServiceEnabled(c: Context): Boolean {
        val enabled = Settings.Secure.getString(
            c.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = ComponentName(c, SoundAccessService::class.java)
        return enabled.split(':').any { flattened ->
            val component = ComponentName.unflattenFromString(flattened)
            component?.packageName == target.packageName && component.className == target.className
        }
    }
}
