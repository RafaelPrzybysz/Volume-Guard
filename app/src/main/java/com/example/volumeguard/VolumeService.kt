package com.example.volumeguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.*

class VolumeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private var screenReceiver: BroadcastReceiver? = null
    private var fallbackReceiver: BroadcastReceiver? = null
    private var screenJob: Job? = null
    private var volumeJob: Job? = null
    private var screenIsOn = false
    private var fallbackTimeout: Runnable? = null
    private var fallbackWakeRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, makeNotification())
        registerScreenReceiver()
        registerFallbackReceiver()
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
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                val after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (after != target) {
                    updateNotification("O Android não aplicou o volume-alvo. ${if (Prefs.uiFallbackEnabled(this@VolumeService)) "Verificando recuperação alternativa…" else "Verifique os limites de volume do sistema."}")
                    tryStartUiFallback()
                } else {
                    updateNotification("Serviço ativo • volume alvo ${Prefs.volume(this@VolumeService)}%")
                }
            }
        } catch (_: SecurityException) {
            updateNotification("O Android bloqueou a alteração do volume.")
            tryStartUiFallback()
        } catch (_: Exception) {
            updateNotification("Não foi possível ajustar o volume.")
        }
    }

    /**
     * Método alternativo opcional: abre as configurações de som e usa duas coordenadas
     * que o usuário calibrou com o serviço de acessibilidade. O ajuste normal por API
     * continua sendo a primeira tentativa.
     */
    private fun tryStartUiFallback() {
        if (!Prefs.uiFallbackEnabled(this)) return
        if (!Prefs.hasSliderPosition(this) || !Prefs.hasOkPosition(this)) {
            updateNotification("Recuperação alternativa ativada: configure as posições da barra e do OK.")
            return
        }
        if (!Prefs.accessibilityServiceEnabled(this)) {
            updateNotification("Recuperação alternativa ativada: habilite o serviço de acessibilidade do Volume Guard.")
            return
        }
        if (Prefs.fallbackInProgress(this)) return

        val now = System.currentTimeMillis()
        val last = Prefs.lastFallbackAttempt(this)
        if (last > 0L && now - last < FALLBACK_COOLDOWN_MS) return

        Prefs.setLastFallbackAttempt(this, now)
        Prefs.setFallbackStartedAt(this, now)
        Prefs.setFallbackScreenWakeAt(this, 0L)
        Prefs.setFallbackInProgress(this, true)
        mainHandler.post { launchSoundSettingsForFallback(now) }
    }

    private fun launchSoundSettingsForFallback(attemptId: Long) {
        if (!Prefs.fallbackInProgress(this) || Prefs.fallbackStartedAt(this) != attemptId) return
        try {
            // Primeiro envia o comando para abrir as configurações de som. A tela só será
            // acordada depois do intervalo configurado pelo usuário.
            val soundIntent = Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(soundIntent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            updateNotification("Configurações de som abertas; aguardando ${Prefs.fallbackStepDelaySeconds(this)} s para acordar a tela…")

            fallbackWakeRunnable?.let { mainHandler.removeCallbacks(it) }
            val stepDelayMs = Prefs.fallbackStepDelaySeconds(this) * 1000L
            val wakeRunnable = Runnable {
                if (Prefs.fallbackInProgress(this) && Prefs.fallbackStartedAt(this) == attemptId) {
                    wakeScreenForFallback(stepDelayMs)
                }
            }
            fallbackWakeRunnable = wakeRunnable
            mainHandler.postDelayed(wakeRunnable, stepDelayMs)

            val timeout = Runnable {
                if (Prefs.fallbackInProgress(this) && Prefs.fallbackStartedAt(this) == attemptId) {
                    Prefs.setFallbackInProgress(this, false)
                    fallbackWakeRunnable?.let { mainHandler.removeCallbacks(it) }
                    fallbackWakeRunnable = null
                    Prefs.setFallbackScreenWakeAt(this, 0L)
                    updateNotification("A recuperação pela tela expirou. Confira a calibração e a acessibilidade.")
                    scheduleScreenLock()
                }
            }
            fallbackTimeout = timeout
            val dynamicTimeout = maxOf(FALLBACK_TIMEOUT_MS, stepDelayMs * 3L + FALLBACK_TIMEOUT_EXTRA_MS)
            mainHandler.postDelayed(timeout, dynamicTimeout)
        } catch (_: Exception) {
            fallbackWakeRunnable?.let { mainHandler.removeCallbacks(it) }
            fallbackWakeRunnable = null
            Prefs.setFallbackInProgress(this, false)
            Prefs.setFallbackScreenWakeAt(this, 0L)
            updateNotification("Não foi possível abrir as configurações de som.")
            scheduleScreenLock()
        }
    }

    private fun wakeScreenForFallback(stepDelayMs: Long) {
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "$packageName:VolumeGuardFallback"
            ).apply { setReferenceCounted(false) }
            val holdMs = maxOf(15_000L, stepDelayMs * 2L + 4_000L)
            wakeLock.acquire(holdMs)
        } catch (_: Exception) {
            // Ainda registramos a etapa para não deixar a recuperação travada se o fabricante
            // não permitir o wake lock; o toque só será tentado depois do intervalo seguinte.
        }
        Prefs.setFallbackScreenWakeAt(this, System.currentTimeMillis())
        updateNotification("Tela acordada; aguardando ${Prefs.fallbackStepDelaySeconds(this)} s antes do toque na barra…")
    }

    private fun registerFallbackReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_FALLBACK_FINISHED) return
                fallbackTimeout?.let { mainHandler.removeCallbacks(it) }
                fallbackTimeout = null
                fallbackWakeRunnable?.let { mainHandler.removeCallbacks(it) }
                fallbackWakeRunnable = null
                Prefs.setFallbackScreenWakeAt(this@VolumeService, 0L)
                val message = intent.getStringExtra(EXTRA_FALLBACK_MESSAGE)
                    ?: "Tentativa de recuperação do volume finalizada."
                updateNotification(message)
                scheduleScreenLock()
            }
        }
        val filter = IntentFilter(ACTION_FALLBACK_FINISHED)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            fallbackReceiver = receiver
        } catch (_: Exception) {}
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
        // Não apagar a tela enquanto o usuário precisa interagir com as configurações de som.
        if (Prefs.fallbackInProgress(this)) {
            scheduleScreenLock()
            return
        }
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, ScreenAdminReceiver::class.java)
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
        fallbackTimeout?.let { mainHandler.removeCallbacks(it) }
        fallbackWakeRunnable?.let { mainHandler.removeCallbacks(it) }
        screenReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        fallbackReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        if (Prefs.fallbackInProgress(this)) Prefs.setFallbackInProgress(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "volume_guard_service"
        const val NOTIFICATION_ID = 4107
        const val ACTION_FALLBACK_FINISHED = "com.example.volumeguard.ACTION_FALLBACK_FINISHED"
        const val EXTRA_FALLBACK_MESSAGE = "extra_fallback_message"
        private const val FALLBACK_COOLDOWN_MS = 10 * 60 * 1000L
        private const val FALLBACK_TIMEOUT_MS = 22_000L
        private const val FALLBACK_TIMEOUT_EXTRA_MS = 18_000L
    }
}
