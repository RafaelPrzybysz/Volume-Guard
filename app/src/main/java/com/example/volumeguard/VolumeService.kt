package com.example.volumeguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.*

class VolumeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var audio: AudioManager
    private var screenReceiver: BroadcastReceiver? = null
    private var screenJob: Job? = null
    private var volumeJob: Job? = null
    private var screenIsOn = false

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, makeNotification())
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs.running(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        restartJobs()
        return START_STICKY
    }

    private fun restartJobs() {
        volumeJob?.cancel()
        volumeJob = scope.launch {
            while (isActive) {
                applyTargetVolume()
                delay(Prefs.volumeInterval(this@VolumeService) * 1000L)
            }
        }
        scheduleScreenLock()
    }

    private fun applyTargetVolume() {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) return
            val target = ((Prefs.volume(this@VolumeService) / 100.0) * max).toInt().coerceIn(0, max)
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current != target) {
                // O Android/fabricante pode rejeitar a mudança por política de segurança.
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                val after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (after != target) updateNotification("O Android não permitiu aplicar o volume solicitado.")
                else updateNotification("Serviço ativo • volume alvo ${Prefs.volume(this)}%")
            }
        } catch (_: SecurityException) {
            updateNotification("O Android bloqueou a alteração do volume.")
        } catch (_: Exception) {
            updateNotification("Não foi possível ajustar o volume.")
        }
    }

    private fun registerScreenReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        screenIsOn = true
                        scheduleScreenLock()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        screenIsOn = false
                        screenJob?.cancel()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            screenReceiver = receiver
        } catch (_: Exception) {}
    }

    private fun scheduleScreenLock() {
        screenJob?.cancel()
        if (!Prefs.screenEnabled(this)) return
        screenJob = scope.launch {
            delay(Prefs.screenInterval(this@VolumeService) * 1000L)
            if (!Prefs.screenEnabled(this@VolumeService)) return@launch
            withContext(Dispatchers.Main) {
                lockScreenIfAuthorized()
            }
        }
    }

    private fun lockScreenIfAuthorized() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = android.content.ComponentName(this, ScreenAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            try {
                dpm.lockNow()
            } catch (_: Exception) {
                updateNotification("Não foi possível bloquear a tela.")
            }
        } else {
            updateNotification("Ative a permissão de administrador para bloquear a tela.")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Volume Guard", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Indica que o controle de volume está ativo." }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun makeNotification(message: String = "Serviço ativo • volume alvo ${Prefs.volume(this)}%"): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Volume Guard")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, makeNotification(message))
    }

    override fun onDestroy() {
        volumeJob?.cancel()
        screenJob?.cancel()
        scope.cancel()
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "volume_guard_service"
        const val NOTIFICATION_ID = 4107
    }
}
