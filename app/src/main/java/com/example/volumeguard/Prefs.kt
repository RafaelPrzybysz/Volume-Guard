package com.example.volumeguard

import android.content.Context

object Prefs {
    private const val FILE = "volume_guard_settings"
    private const val RUNNING = "running"
    private const val VOLUME = "volume_percent"
    private const val VOLUME_INTERVAL = "volume_interval_seconds"
    private const val SCREEN_ENABLED = "screen_lock_enabled"
    private const val SCREEN_INTERVAL = "screen_interval_seconds"
    private const val START_BOOT = "start_on_boot"

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
}
