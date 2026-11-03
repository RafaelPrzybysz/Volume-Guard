package com.example.volumeguard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.media.AudioManager
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.widget.Toast
import java.text.Normalizer
import java.util.Locale

/**
 * Serviço opcional e ativado manualmente pelo usuário.
 * É usado somente para o modo de recuperação configurado na tela do Volume Guard
 * e para permitir a calibração dos pontos de toque da recuperação e do diálogo de boot.
 */
class SoundAccessService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var bootPendingReceiver: BroadcastReceiver? = null

    private var markerView: View? = null
    private var markerParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var calibrationShownFor = Prefs.TARGET_NONE
    private var markerSizePx = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0

    private var observedFallbackStart = 0L
    private var sliderTapScheduled = false
    private var sliderTapSent = false
    private var sliderTapCount = 0
    private var splitToggleSent = false
    private var splitNormalizationWaiting = false
    private var bootTapScheduled = false
    private var bootPollScheduled = false
    private var okTapScheduled = false
    private var okTapSent = false
    private var fallbackTapElapsed = 0L

    private val delayedSliderTap = Runnable {
        sliderTapScheduled = false
        if (!Prefs.fallbackInProgress(this)) return@Runnable
        val root = rootInActiveWindow
        if (Prefs.fallbackSplitDetected(this) && isSplitScreenShowing()) {
            completeFallback("A tela continua dividida e o Android não permitiu voltar ao modo normal; nenhum toque foi feito para evitar clicar no local errado.")
            return@Runnable
        }
        if (!isSoundSettingsWindow(root)) {
            val started = Prefs.fallbackStartedAt(this)
            val openTimeout = maxOf(
                SETTINGS_OPEN_TIMEOUT_BASE_MS,
                Prefs.fallbackStepDelaySeconds(this) * 2000L + SETTINGS_OPEN_EXTRA_MS
            )
            if (System.currentTimeMillis() - started < openTimeout) {
                scheduleSliderTap(450L)
            } else {
                completeFallback("Não foi possível confirmar a tela de som; nenhum toque foi feito para evitar tocar na tela errada.")
            }
            return@Runnable
        }
        // O toque ocorre um intervalo depois de a tela ser acordada; se o serviço
        // ainda não registrou o despertar, usa como referência o início + dois intervalos.
        val remaining = sliderTapDueAt() - System.currentTimeMillis()
        if (remaining > 0L) {
            scheduleSliderTap(remaining)
            return@Runnable
        }
        performConfiguredSliderTap()
    }

    private val delayedFallbackCheck = Runnable { checkFallbackAfterSliderTap() }
    private val delayedSplitNormalizationCheck = Runnable {
        splitNormalizationWaiting = false
        if (!Prefs.fallbackInProgress(this)) return@Runnable
        if (isSplitScreenShowing()) {
            completeFallback("O Android não conseguiu sair da tela dividida. Nenhum toque foi feito; tente novamente com a tela em modo normal.")
        } else {
            handler.postDelayed({ scheduleSliderTap(300L) }, 300L)
        }
    }
    private val delayedOkTap = Runnable {
        okTapScheduled = false
        if (!Prefs.fallbackInProgress(this) || okTapSent) return@Runnable
        val root = rootInActiveWindow
        // A coordenada OK só é usada se o aviso ainda estiver visível após a espera configurada.
        if (isVolumeWarningVisible(root)) {
            performConfiguredOkTap()
        } else if (isTargetVolumeApplied()) {
            completeFallback("Volume aplicado antes de precisar confirmar o aviso.")
        } else {
            handler.postDelayed(delayedFallbackCheck, FALLBACK_CHECK_INTERVAL_MS)
        }
    }

    private val delayedBootPoll = Runnable {
        bootPollScheduled = false
        handleBootDialog(rootInActiveWindow)
    }

    private val delayedBootTap = Runnable {
        bootTapScheduled = false
        if (!Prefs.bootTapEligible(this) || Prefs.calibrationActive(this)) return@Runnable
        val pendingAt = Prefs.bootTapPendingAt(this)
        if (pendingAt <= 0L || System.currentTimeMillis() - pendingAt > BOOT_DIALOG_WINDOW_MS) {
            Prefs.clearBootTapPending(this)
            return@Runnable
        }
        val root = rootInActiveWindow
        if (!isBootOkDialogVisible(root)) {
            scheduleBootDialogPoll()
            return@Runnable
        }
        val point = Prefs.position(this, Prefs.TARGET_BOOT_OK)
        if (point == null) {
            Prefs.clearBootTapPending(this)
            return@Runnable
        }
        Prefs.setBootTapSentAt(this, System.currentTimeMillis())
        val accepted = dispatchTap(point.first, point.second) { completed ->
            Prefs.clearBootTapPending(this)
            Toast.makeText(
                this,
                if (completed) "Toque de inicialização executado." else "O Android não aceitou o toque do diálogo de inicialização.",
                Toast.LENGTH_LONG
            ).show()
        }
        if (!accepted) {
            Prefs.clearBootTapPending(this)
            Toast.makeText(this, "Não foi possível executar o toque de inicialização.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        registerBootPendingReceiver()
        maybeShowCalibrationOverlay()
        handler.postDelayed({ handleBootDialog(rootInActiveWindow) }, 700L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventPackage = event.packageName?.toString().orEmpty()
        val root = rootInActiveWindow
        val rootPackage = root?.packageName?.toString().orEmpty()

        if (Prefs.calibrationActive(this)) {
            val target = Prefs.calibrationTarget(this)
            val shouldShow = when (target) {
                Prefs.TARGET_BOOT_OK -> isBootOkDialogVisible(root)
                Prefs.TARGET_SLIDER, Prefs.TARGET_OK ->
                    isSettingsPackage(eventPackage) || isSettingsPackage(rootPackage)
                else -> false
            }
            if (shouldShow) showCalibrationOverlay(target)
        }

        if (!Prefs.calibrationActive(this)) handleBootDialog(root)

        if (Prefs.fallbackInProgress(this)) {
            prepareForNewFallbackAttemptIfNeeded()
            if (isVolumeWarningVisible(root)) {
                confirmVolumeWarningIfConfigured()
            } else if (isSettingsPackage(eventPackage) || isSettingsPackage(rootPackage) || isSoundSettingsWindowVisible(root)) {
                val splitHandled = normalizeSplitScreenIfNeeded()
                if (!splitHandled) scheduleSliderTap()
            }
        } else {
            resetFallbackState()
        }
    }

    override fun onInterrupt() {
        // Nenhuma ação necessária; o usuário pode desativar o serviço nas configurações.
    }

    override fun onDestroy() {
        handler.removeCallbacks(delayedSliderTap)
        handler.removeCallbacks(delayedFallbackCheck)
        handler.removeCallbacks(delayedOkTap)
        handler.removeCallbacks(delayedBootTap)
        handler.removeCallbacks(delayedBootPoll)
        handler.removeCallbacks(delayedSplitNormalizationCheck)
        bootPendingReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        bootPendingReceiver = null
        removeCalibrationOverlay()
        super.onDestroy()
    }

    private fun prepareForNewFallbackAttemptIfNeeded() {
        val currentStart = Prefs.fallbackStartedAt(this)
        if (currentStart != observedFallbackStart) {
            observedFallbackStart = currentStart
            sliderTapScheduled = false
            sliderTapSent = false
            sliderTapCount = 0
            splitToggleSent = false
            splitNormalizationWaiting = false
            handler.removeCallbacks(delayedSplitNormalizationCheck)
            Prefs.setFallbackSplitDetected(this, false)
            Prefs.setFallbackSplitToggleTried(this, false)
            okTapSent = false
            fallbackTapElapsed = 0L
            okTapScheduled = false
            handler.removeCallbacks(delayedSliderTap)
            handler.removeCallbacks(delayedFallbackCheck)
            handler.removeCallbacks(delayedOkTap)
        }
    }

    private fun resetFallbackState() {
        if (!sliderTapScheduled && !sliderTapSent && sliderTapCount == 0 && !okTapSent && !splitNormalizationWaiting) return
        handler.removeCallbacks(delayedSliderTap)
        handler.removeCallbacks(delayedFallbackCheck)
        handler.removeCallbacks(delayedOkTap)
        sliderTapScheduled = false
        sliderTapSent = false
        sliderTapCount = 0
        splitToggleSent = false
        splitNormalizationWaiting = false
        handler.removeCallbacks(delayedSplitNormalizationCheck)
        okTapScheduled = false
        okTapSent = false
        fallbackTapElapsed = 0L
        observedFallbackStart = 0L
    }

    private fun isSettingsPackage(pkg: String): Boolean =
        pkg.contains("settings", ignoreCase = true)

    /** Exige indícios de que a janela aberta é a tela de som, não qualquer página de Configurações. */
    private fun isSoundSettingsWindow(root: AccessibilityNodeInfo?): Boolean {
        if (root == null || !isSettingsPackage(root.packageName?.toString().orEmpty())) return false
        val pieces = ArrayList<String>()
        collectText(root, pieces)
        val visibleText = normalize(pieces.joinToString(" "))
        val mediaVolumeLabels = listOf(
            "media volume", "volume de midia", "volume da midia", "volume multimidia", "volume multimedia"
        )
        if (mediaVolumeLabels.any { visibleText.contains(it) }) return true
        val soundPageCues = listOf(
            "sound", "som", "audio", "vibration", "vibracao", "ringtone", "toque",
            "alarm", "alarme", "do not disturb", "nao perturbe"
        )
        return visibleText.contains("volume") && soundPageCues.any { visibleText.contains(it) }
    }

    /** Procura a tela de som em qualquer janela acessível, útil quando duas apps estavam lado a lado. */
    private fun isSoundSettingsWindowVisible(activeRoot: AccessibilityNodeInfo?): Boolean {
        if (isSoundSettingsWindow(activeRoot)) return true
        return anyVisibleWindowMatches { isSoundSettingsWindow(it) }
    }

    /** Alguns avisos do Android aparecem numa janela separada do app Configurações. */
    private fun isVolumeWarningVisible(activeRoot: AccessibilityNodeInfo?): Boolean {
        if (isVolumeWarning(activeRoot)) return true
        return anyVisibleWindowMatches { isVolumeWarning(it) }
    }

    private fun isBootOkDialogVisible(activeRoot: AccessibilityNodeInfo?): Boolean {
        if (isBootOkDialog(activeRoot)) return true
        return anyVisibleWindowMatches { isBootOkDialog(it) }
    }

    private fun anyVisibleWindowMatches(match: (AccessibilityNodeInfo) -> Boolean): Boolean {
        return try {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                    window.type != AccessibilityWindowInfo.TYPE_SYSTEM &&
                    window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val node = try { window.root } catch (_: Exception) { null } ?: continue
                try {
                    if (match(node)) return true
                } finally {
                    try { node.recycle() } catch (_: Exception) {}
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun registerBootPendingReceiver() {
        if (bootPendingReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BootReceiver.ACTION_BOOT_TAP_PENDING ->
                        handler.postDelayed({ handleBootDialog(rootInActiveWindow) }, 500L)
                    VolumeService.ACTION_FALLBACK_TIMED_OUT -> {
                        handler.removeCallbacks(delayedSliderTap)
                        handler.removeCallbacks(delayedFallbackCheck)
                        handler.removeCallbacks(delayedOkTap)
                        handler.removeCallbacks(delayedSplitNormalizationCheck)
                        val activePackage = rootInActiveWindow?.packageName?.toString().orEmpty()
                        if (isSettingsPackage(activePackage)) performGlobalAction(GLOBAL_ACTION_BACK)
                        handler.postDelayed({ OrientationControl.restore(this@SoundAccessService) }, 650L)
                        resetFallbackState()
                    }
                }
            }
        }
        val filter = IntentFilter(BootReceiver.ACTION_BOOT_TAP_PENDING).apply {
            addAction(VolumeService.ACTION_FALLBACK_TIMED_OUT)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            bootPendingReceiver = receiver
        } catch (_: Exception) {
            bootPendingReceiver = null
        }
    }

    private fun scheduleSliderTap(delayMs: Long? = null) {
        // Não agenda outro gesto enquanto o resultado do toque anterior ainda está sendo verificado.
        if (sliderTapScheduled || sliderTapSent || sliderTapCount >= MAX_SLIDER_TAPS || !Prefs.fallbackInProgress(this)) return
        sliderTapScheduled = true
        val now = System.currentTimeMillis()
        val delay = delayMs ?: (sliderTapDueAt() - now).coerceAtLeast(0L)
        handler.postDelayed(delayedSliderTap, delay)
    }

    private fun sliderTapDueAt(): Long {
        val stepMs = Prefs.fallbackStepDelaySeconds(this) * 1000L
        val wakeAt = Prefs.fallbackScreenWakeAt(this)
        val startedAt = Prefs.fallbackStartedAt(this)
        return if (wakeAt > 0L) wakeAt + stepMs else startedAt + (stepMs * 2L)
    }

    private fun performConfiguredSliderTap() {
        if (!Prefs.fallbackInProgress(this) || sliderTapCount >= MAX_SLIDER_TAPS) return
        val position = Prefs.position(this, Prefs.TARGET_SLIDER)
        if (position == null) {
            completeFallback("Configure primeiro a posição da barra Media volume.")
            return
        }
        sliderTapSent = true
        sliderTapCount += 1
        val accepted = dispatchTap(position.first, position.second) { completed ->
            if (!completed) {
                completeFallback("O Android não aceitou o toque na barra de volume.")
            } else {
                fallbackTapElapsed = SystemClock.elapsedRealtime()
                handler.postDelayed(delayedFallbackCheck, FALLBACK_CHECK_INTERVAL_MS)
            }
        }
        if (!accepted) completeFallback("Não foi possível executar o toque na barra de volume.")
    }

    private fun checkFallbackAfterSliderTap() {
        if (!Prefs.fallbackInProgress(this) || !sliderTapSent) return
        val root = rootInActiveWindow
        if (isVolumeWarningVisible(root)) {
            confirmVolumeWarningIfConfigured()
            return
        }

        if (isTargetVolumeApplied()) {
            completeFallback("Volume aplicado pelo painel de som.")
            return
        }

        // Se Settings só aparece em uma janela de fundo, não repete o toque: pode haver
        // um diálogo modal não reconhecido por cima da barra. Aguarda o próximo evento.
        if (!isSoundSettingsWindow(root) && isSoundSettingsWindowVisible(root)) {
            handler.postDelayed(delayedFallbackCheck, FALLBACK_CHECK_INTERVAL_MS)
            return
        }

        val elapsed = SystemClock.elapsedRealtime() - fallbackTapElapsed
        val retryDelay = Prefs.fallbackStepDelaySeconds(this) * 1000L
        if (elapsed >= retryDelay && sliderTapCount < MAX_SLIDER_TAPS && isSoundSettingsWindow(root)) {
            // Reaplica o toque no ponto salvo até cinco vezes, verificando o aviso e o nível real entre elas.
            sliderTapSent = false
            performConfiguredSliderTap()
            return
        }

        val finalWait = retryDelay + FALLBACK_CONFIRM_EXTRA_MS
        if (sliderTapCount >= MAX_SLIDER_TAPS && elapsed >= finalWait) {
            completeFallback("A tela de som foi tocada ${sliderTapCount} vezes, mas o volume-alvo não foi confirmado. Confira os pontos salvos.")
        } else {
            handler.postDelayed(delayedFallbackCheck, FALLBACK_CHECK_INTERVAL_MS)
        }
    }

    private fun confirmVolumeWarningIfConfigured() {
        if (okTapSent || okTapScheduled || !Prefs.fallbackInProgress(this)) return
        if (Prefs.position(this, Prefs.TARGET_OK) == null) {
            completeFallback("O aviso apareceu, mas a posição do botão OK não foi configurada.")
            return
        }
        // Espera o intervalo escolhido e valida de novo que o aviso continua na tela.
        okTapScheduled = true
        handler.removeCallbacks(delayedFallbackCheck)
        handler.postDelayed(delayedOkTap, Prefs.fallbackStepDelaySeconds(this) * 1000L)
    }

    private fun performConfiguredOkTap() {
        if (okTapSent || !Prefs.fallbackInProgress(this)) return
        val position = Prefs.position(this, Prefs.TARGET_OK)
        if (position == null) {
            completeFallback("O aviso apareceu, mas a posição do botão OK não foi configurada.")
            return
        }
        okTapSent = true
        val accepted = dispatchTap(position.first, position.second) { completed ->
            if (!completed) {
                completeFallback("Não foi possível tocar na posição configurada do OK.")
            } else {
                handler.postDelayed({
                    if (isTargetVolumeApplied()) {
                        completeFallback("Volume aplicado após confirmar o aviso.")
                    } else {
                        completeFallback("O aviso foi tocado, mas o volume-alvo não foi confirmado. Verifique a posição da barra.")
                    }
                }, 900L)
            }
        }
        if (!accepted) completeFallback("Não foi possível executar o toque no botão OK.")
    }

    private fun isTargetVolumeApplied(): Boolean {
        return try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) return false
            val target = ((Prefs.volume(this) / 100.0) * max).toInt().coerceIn(0, max)
            audio.getStreamVolume(AudioManager.STREAM_MUSIC) == target
        } catch (_: Exception) {
            false
        }
    }

    private fun isVolumeWarning(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val pieces = ArrayList<String>()
        collectText(root, pieces)
        val combined = normalize(pieces.joinToString(" "))
        val hasVolume = combined.contains("volume") || combined.contains("headphone") || combined.contains("fone") || combined.contains("audio")
        val hasSafetyCue = combined.contains("recommended") || combined.contains("recomendado") ||
            combined.contains("safe volume") || combined.contains("volume seguro") ||
            combined.contains("safety") || combined.contains("seguranca") ||
            combined.contains("protecao auditiva") || combined.contains("hearing protection") ||
            combined.contains("limite de volume")
        return hasVolume && hasSafetyCue
    }

    /** Reconhece um diálogo que expõe um botão positivo com rótulo exato OK. */
    private fun isBootOkDialog(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        if (root.packageName?.toString() == packageName) return false
        if (isVolumeWarning(root)) return false
        return containsExactOkButton(root)
    }

    private fun containsExactOkButton(node: AccessibilityNodeInfo): Boolean {
        val label = normalize(node.text?.toString().orEmpty().trim())
        val description = normalize(node.contentDescription?.toString().orEmpty().trim())
        val className = node.className?.toString().orEmpty().lowercase(Locale.ROOT)
        val isOkLabel = label == "ok" || label == "okay" || description == "ok" || description == "okay"
        if (isOkLabel && (node.isClickable || className.contains("button") || className.contains("textview"))) return true
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val found = containsExactOkButton(child)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun handleBootDialog(root: AccessibilityNodeInfo?) {
        if (!Prefs.bootTapEligible(this) || Prefs.calibrationActive(this)) return
        val pendingAt = Prefs.bootTapPendingAt(this)
        if (pendingAt <= 0L) return
        if (System.currentTimeMillis() - pendingAt > BOOT_DIALOG_WINDOW_MS) {
            Prefs.clearBootTapPending(this)
            handler.removeCallbacks(delayedBootPoll)
            bootPollScheduled = false
            return
        }
        if (Prefs.bootTapSentAt(this) > 0L || bootTapScheduled) return
        if (!isBootOkDialogVisible(root)) {
            scheduleBootDialogPoll()
            return
        }
        if (Prefs.position(this, Prefs.TARGET_BOOT_OK) == null) {
            Prefs.clearBootTapPending(this)
            return
        }
        bootTapScheduled = true
        handler.postDelayed(delayedBootTap, Prefs.fallbackStepDelaySeconds(this) * 1000L)
    }

    private fun scheduleBootDialogPoll() {
        if (bootPollScheduled || !Prefs.bootTapEligible(this) || Prefs.calibrationActive(this)) return
        val pendingAt = Prefs.bootTapPendingAt(this)
        if (pendingAt <= 0L || System.currentTimeMillis() - pendingAt > BOOT_DIALOG_WINDOW_MS) return
        bootPollScheduled = true
        handler.postDelayed(delayedBootPoll, BOOT_DIALOG_POLL_INTERVAL_MS)
    }

    /** Detecta duas janelas de aplicativos em regiões separadas da tela. */
    private fun isSplitScreenShowing(): Boolean {
        return try {
            val visibleWindows = windows
            if (Build.VERSION.SDK_INT >= 24 && visibleWindows.any {
                    it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
                }) return true
            val manager = wm ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { wm = it }
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealMetrics(metrics)
            val totalArea = metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
            if (totalArea <= 0L) return false
            val appBounds = ArrayList<Rect>()
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = window.root ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                root.recycle()
                val area = bounds.width().coerceAtLeast(0).toLong() * bounds.height().coerceAtLeast(0).toLong()
                if (pkg.isNotBlank() && area > totalArea / 10L) appBounds.add(bounds)
            }
            if (appBounds.size < 2) return false
            appBounds.count { rect ->
                rect.width() < metrics.widthPixels * 0.96 || rect.height() < metrics.heightPixels * 0.96
            } >= 2
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Detecta tela dividida e tenta tirar a janela de Configurações do modo encaixado.
     * Retorna true enquanto a situação dividida foi detectada/está sendo normalizada,
     * para impedir que coordenadas de tela cheia sejam usadas dentro de meia tela.
     */
    private fun normalizeSplitScreenIfNeeded(): Boolean {
        if (!isSplitScreenShowing()) return splitNormalizationWaiting
        if (!Prefs.fallbackInProgress(this)) return true
        Prefs.setFallbackSplitDetected(this, true)
        if (splitToggleSent) return true

        splitToggleSent = true
        // Android 9/API 28 oferece esta ação apenas quando a ROM a publica em getSystemActions().
        val changed = if (Build.VERSION.SDK_INT >= 24) {
            performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        } else false
        Prefs.setFallbackSplitToggleTried(this, changed)
        if (!changed) {
            completeFallback("A ROM não disponibilizou a ação para sair da tela dividida. Nenhum toque foi feito para evitar usar coordenadas erradas.")
            return true
        }

        splitNormalizationWaiting = true
        handler.removeCallbacks(delayedSplitNormalizationCheck)
        handler.postDelayed(delayedSplitNormalizationCheck, SPLIT_TRANSITION_WAIT_MS)
        return true
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) {
                collectText(child, out)
                child.recycle()
            }
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")

    private fun dispatchTap(xRatio: Float, yRatio: Float, finished: (Boolean) -> Unit): Boolean {
        val manager = wm ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { wm = it }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return false
        var adjustedX = xRatio.coerceIn(0f, 1f)
        var adjustedY = yRatio.coerceIn(0f, 1f)
        // As posições são calibradas em retrato. Se a permissão de rotação não foi
        // concedida e a tela continua em paisagem, converte a coordenada para a rotação atual.
        if (metrics.widthPixels > metrics.heightPixels) {
            @Suppress("DEPRECATION")
            when (manager.defaultDisplay.rotation) {
                android.view.Surface.ROTATION_90 -> {
                    val oldX = adjustedX
                    adjustedX = 1f - adjustedY
                    adjustedY = oldX
                }
                android.view.Surface.ROTATION_180 -> {
                    adjustedX = 1f - adjustedX
                    adjustedY = 1f - adjustedY
                }
                android.view.Surface.ROTATION_270 -> {
                    val oldX = adjustedX
                    adjustedX = adjustedY
                    adjustedY = 1f - oldX
                }
            }
        }
        val x = (adjustedX * (metrics.widthPixels - 1)).coerceIn(0f, (metrics.widthPixels - 1).toFloat())
        val y = (adjustedY * (metrics.heightPixels - 1)).coerceIn(0f, (metrics.heightPixels - 1).toFloat())
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 90L))
            .build()
        return dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                finished(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                finished(false)
            }
        }, handler)
    }

    private fun completeFallback(message: String) {
        if (!Prefs.fallbackInProgress(this)) return
        Prefs.setFallbackInProgress(this, false)
        handler.removeCallbacks(delayedSliderTap)
        handler.removeCallbacks(delayedFallbackCheck)
        handler.removeCallbacks(delayedOkTap)
        val intent = Intent(VolumeService.ACTION_FALLBACK_FINISHED).apply {
            setPackage(packageName)
            putExtra(VolumeService.EXTRA_FALLBACK_MESSAGE, message)
        }
        sendBroadcast(intent)
        // Fecha a página de som para tentar retornar ao aplicativo/tela que estava em uso.
        // Se havia tela dividida, o sistema pode restaurar o par anterior ao fechar a atividade;
        // Android 9/ROMs não oferecem garantia pública de restauração exata do par.
        val activePackage = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (isSettingsPackage(activePackage)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({ OrientationControl.restore(this) }, 650L)
        } else {
            OrientationControl.restore(this)
        }
        resetFallbackState()
    }

    private fun maybeShowCalibrationOverlay() {
        if (!Prefs.calibrationActive(this)) return
        val target = Prefs.calibrationTarget(this)
        if (target == Prefs.TARGET_NONE) return
        val root = rootInActiveWindow
        val rootPackage = root?.packageName?.toString().orEmpty()
        if (target == Prefs.TARGET_BOOT_OK && isBootOkDialogVisible(root)) {
            showCalibrationOverlay(target)
        } else if (isSettingsPackage(rootPackage) || isSoundSettingsWindowVisible(root)) {
            showCalibrationOverlay(target)
        }
    }

    private fun showCalibrationOverlay(target: String) {
        if (markerView != null && calibrationShownFor == target) return
        removeCalibrationOverlay()
        val manager = wm ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { wm = it }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        val screenW = metrics.widthPixels.coerceAtLeast(1)
        val screenH = metrics.heightPixels.coerceAtLeast(1)
        markerSizePx = dp(50)
        val savedPosition = Prefs.position(this, target)
        val markerX = savedPosition?.first?.let { (it * screenW).toInt() - markerSizePx / 2 }
            ?: (screenW - markerSizePx) / 2
        val markerY = savedPosition?.second?.let { (it * screenH).toInt() - markerSizePx / 2 }
            ?: (screenH - markerSizePx) / 2

        val marker = FrameLayout(this).apply {
            val outer = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xDDFF3B30.toInt())
                setStroke(dp(3), 0xFFFFFFFF.toInt())
            }
            background = outer
            elevation = dp(8).toFloat()
            contentDescription = "Bolinha de calibração; arraste até o local do toque"
            addView(TextView(this@SoundAccessService).apply {
                text = "•"
                textSize = 32f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
            }, FrameLayout.LayoutParams(-1, -1))
        }
        val markerLp = WindowManager.LayoutParams(
            markerSizePx,
            markerSizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = markerX.coerceIn(0, (screenW - markerSizePx).coerceAtLeast(0))
            y = markerY.coerceIn(0, (screenH - markerSizePx).coerceAtLeast(0))
        }
        marker.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downWindowX = markerLp.x
                    downWindowY = markerLp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    markerLp.x = (downWindowX + (event.rawX - downRawX).toInt())
                        .coerceIn(0, (screenW - markerSizePx).coerceAtLeast(0))
                    markerLp.y = (downWindowY + (event.rawY - downRawY).toInt())
                        .coerceIn(0, (screenH - markerSizePx).coerceAtLeast(0))
                    try { manager.updateViewLayout(marker, markerLp) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }

        try {
            manager.addView(marker, markerLp)
            markerView = marker
            markerParams = markerLp
            val panel = createCalibrationPanel(target)
            panelView = panel
            val panelLp = WindowManager.LayoutParams(
                dp(320),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(48)
            }
            manager.addView(panel, panelLp)
            panelParams = panelLp
            calibrationShownFor = target
        } catch (_: Exception) {
            removeCalibrationOverlay()
            Toast.makeText(this, "Não foi possível mostrar o marcador de calibração neste aparelho.", Toast.LENGTH_LONG).show()
        }
    }

    private fun createCalibrationPanel(target: String): View {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(0xEE202124.toInt())
            setStroke(dp(1), 0xFF777777.toInt())
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = bg
            elevation = dp(10).toFloat()
        }
        val heading = TextView(this).apply {
            text = when (target) {
                Prefs.TARGET_SLIDER -> "Calibrar barra Media volume"
                Prefs.TARGET_OK -> "Calibrar botão OK do aviso de volume"
                else -> "Calibrar OK do diálogo de inicialização"
            }
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val instructions = TextView(this).apply {
            text = when (target) {
                Prefs.TARGET_SLIDER -> "Arraste a bolinha para a barra, no nível correspondente ao volume-alvo. Depois toque em Salvar posição."
                Prefs.TARGET_OK -> "Toque manualmente na barra Media volume até aparecer o aviso. Com o aviso aberto, arraste a bolinha sobre OK e toque em Salvar posição."
                else -> "Com o diálogo da ROM visível após reiniciar, arraste a bolinha sobre o botão OK e salve esta terceira posição."
            }
            textSize = 12f
            setTextColor(0xFFF1F1F1.toInt())
            setPadding(0, dp(4), 0, dp(8))
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val save = Button(this).apply {
            text = "Salvar posição"
            isAllCaps = false
            setOnClickListener { saveCalibrationPosition(target) }
        }
        val cancel = Button(this).apply {
            text = "Cancelar"
            isAllCaps = false
            setOnClickListener {
                Prefs.setCalibrationActive(this@SoundAccessService, false)
                Prefs.setCalibrationTarget(this@SoundAccessService, Prefs.TARGET_NONE)
                removeCalibrationOverlay()
                Toast.makeText(this@SoundAccessService, "Calibração cancelada.", Toast.LENGTH_SHORT).show()
            }
        }
        buttons.addView(save, LinearLayout.LayoutParams(0, dp(46), 1f))
        buttons.addView(cancel, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(6) })
        panel.addView(heading)
        panel.addView(instructions)
        panel.addView(buttons)
        return panel
    }

    private fun saveCalibrationPosition(target: String) {
        val lp = markerParams
        val manager = wm
        if (lp == null || manager == null) return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels.coerceAtLeast(1)
        val h = metrics.heightPixels.coerceAtLeast(1)
        val x = (lp.x + markerSizePx / 2f) / w.toFloat()
        val y = (lp.y + markerSizePx / 2f) / h.toFloat()
        Prefs.setPosition(this, target, x, y)
        Prefs.setCalibrationActive(this, false)
        Prefs.setCalibrationTarget(this, Prefs.TARGET_NONE)
        removeCalibrationOverlay()
        val label = when (target) {
            Prefs.TARGET_SLIDER -> "Barra Media volume"
            Prefs.TARGET_OK -> "Botão OK do aviso de volume"
            else -> "Botão OK da inicialização"
        }
        Toast.makeText(this, "$label salvo.", Toast.LENGTH_LONG).show()
        if (target == Prefs.TARGET_BOOT_OK) {
            handler.postDelayed({ handleBootDialog(rootInActiveWindow) }, 150L)
        }
    }

    private fun removeCalibrationOverlay() {
        val manager = wm
        try { markerView?.let { manager?.removeView(it) } } catch (_: Exception) {}
        try { panelView?.let { manager?.removeView(it) } } catch (_: Exception) {}
        markerView = null
        markerParams = null
        panelView = null
        panelParams = null
        calibrationShownFor = Prefs.TARGET_NONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SETTINGS_OPEN_TIMEOUT_BASE_MS = 8000L
        private const val SETTINGS_OPEN_EXTRA_MS = 5000L
        private const val FALLBACK_CHECK_INTERVAL_MS = 350L
        private const val FALLBACK_CONFIRM_EXTRA_MS = 3500L
        private const val MAX_SLIDER_TAPS = 5
        private const val SPLIT_TRANSITION_WAIT_MS = 900L
        private const val BOOT_DIALOG_WINDOW_MS = 180_000L
        private const val BOOT_DIALOG_POLL_INTERVAL_MS = 1000L
    }
}
