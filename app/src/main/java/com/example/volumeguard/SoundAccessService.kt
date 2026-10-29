package com.example.volumeguard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.media.AudioManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.text.Normalizer
import java.util.Locale

/**
 * Serviço opcional e ativado manualmente pelo usuário.
 * É usado somente para o modo de recuperação configurado na tela do Volume Guard
 * e para permitir a calibração dos dois pontos de toque.
 */
class SoundAccessService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null

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
    private var okTapScheduled = false
    private var okTapSent = false
    private var fallbackTapElapsed = 0L

    private val delayedSliderTap = Runnable {
        sliderTapScheduled = false
        if (!Prefs.fallbackInProgress(this)) return@Runnable
        val root = rootInActiveWindow
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
    private val delayedOkTap = Runnable {
        okTapScheduled = false
        if (!Prefs.fallbackInProgress(this) || okTapSent) return@Runnable
        val root = rootInActiveWindow
        // A coordenada OK só é usada se o aviso ainda estiver visível após a espera configurada.
        if (isVolumeWarning(root)) {
            performConfiguredOkTap()
        } else if (isTargetVolumeApplied()) {
            completeFallback("Volume aplicado antes de precisar confirmar o aviso.")
        } else {
            handler.postDelayed(delayedFallbackCheck, FALLBACK_CHECK_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        maybeShowCalibrationOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventPackage = event.packageName?.toString().orEmpty()
        val root = rootInActiveWindow
        val rootPackage = root?.packageName?.toString().orEmpty()

        if (Prefs.calibrationActive(this)) {
            val target = Prefs.calibrationTarget(this)
            if (target != Prefs.TARGET_NONE && (isSettingsPackage(eventPackage) || isSettingsPackage(rootPackage))) {
                showCalibrationOverlay(target)
            }
        }

        if (Prefs.fallbackInProgress(this)) {
            prepareForNewFallbackAttemptIfNeeded()
            if (isVolumeWarning(root)) {
                confirmVolumeWarningIfConfigured()
            } else if (isSettingsPackage(eventPackage) || isSettingsPackage(rootPackage)) {
                scheduleSliderTap()
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
        removeCalibrationOverlay()
        super.onDestroy()
    }

    private fun prepareForNewFallbackAttemptIfNeeded() {
        val currentStart = Prefs.fallbackStartedAt(this)
        if (currentStart != observedFallbackStart) {
            observedFallbackStart = currentStart
            sliderTapScheduled = false
            sliderTapSent = false
            okTapSent = false
            fallbackTapElapsed = 0L
            okTapScheduled = false
            handler.removeCallbacks(delayedSliderTap)
            handler.removeCallbacks(delayedFallbackCheck)
            handler.removeCallbacks(delayedOkTap)
        }
    }

    private fun resetFallbackState() {
        if (!sliderTapScheduled && !sliderTapSent && !okTapSent) return
        handler.removeCallbacks(delayedSliderTap)
        handler.removeCallbacks(delayedFallbackCheck)
        handler.removeCallbacks(delayedOkTap)
        sliderTapScheduled = false
        sliderTapSent = false
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

    private fun scheduleSliderTap(delayMs: Long? = null) {
        if (sliderTapScheduled || sliderTapSent || !Prefs.fallbackInProgress(this)) return
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
        if (sliderTapSent || !Prefs.fallbackInProgress(this)) return
        val position = Prefs.position(this, Prefs.TARGET_SLIDER)
        if (position == null) {
            completeFallback("Configure primeiro a posição da barra Media volume.")
            return
        }
        sliderTapSent = true
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
        if (isVolumeWarning(root)) {
            confirmVolumeWarningIfConfigured()
            return
        }

        if (isTargetVolumeApplied()) {
            completeFallback("Volume aplicado pelo painel de som.")
            return
        }

        val elapsed = SystemClock.elapsedRealtime() - fallbackTapElapsed
        val confirmTimeout = Prefs.fallbackStepDelaySeconds(this) * 1000L + FALLBACK_CONFIRM_EXTRA_MS
        if (elapsed >= confirmTimeout) {
            completeFallback("A tentativa pela tela não confirmou o volume. Confira as duas posições salvas.")
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
        val x = (xRatio.coerceIn(0f, 1f) * (metrics.widthPixels - 1)).coerceIn(0f, (metrics.widthPixels - 1).toFloat())
        val y = (yRatio.coerceIn(0f, 1f) * (metrics.heightPixels - 1)).coerceIn(0f, (metrics.heightPixels - 1).toFloat())
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
        resetFallbackState()
    }

    private fun maybeShowCalibrationOverlay() {
        if (!Prefs.calibrationActive(this)) return
        val target = Prefs.calibrationTarget(this)
        if (target == Prefs.TARGET_NONE) return
        val rootPackage = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (isSettingsPackage(rootPackage)) showCalibrationOverlay(target)
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
            text = if (target == Prefs.TARGET_SLIDER) "Calibrar barra Media volume" else "Calibrar botão OK"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val instructions = TextView(this).apply {
            text = if (target == Prefs.TARGET_SLIDER) {
                "Arraste a bolinha para a barra, no nível correspondente ao volume-alvo. Depois toque em Salvar posição."
            } else {
                "Toque manualmente na barra Media volume até aparecer o aviso. Com o aviso aberto, arraste a bolinha sobre OK e toque em Salvar posição."
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
        val label = if (target == Prefs.TARGET_SLIDER) "Barra Media volume" else "Botão OK"
        Toast.makeText(this, "$label salvo.", Toast.LENGTH_LONG).show()
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
    }
}
