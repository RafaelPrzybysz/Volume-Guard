package com.example.volumeguard

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var volumeSeek: SeekBar
    private lateinit var volumeValue: TextView
    private lateinit var volumeInterval: EditText
    private lateinit var screenSwitch: Switch
    private lateinit var screenInterval: EditText
    private lateinit var bootSwitch: Switch
    private lateinit var adminStatus: TextView
    private lateinit var fallbackSwitch: Switch
    private lateinit var fallbackStatus: TextView
    private lateinit var fallbackStepDelay: EditText
    private lateinit var portraitSwitch: Switch
    private lateinit var portraitStatus: TextView
    private lateinit var bootDialogSwitch: Switch
    private lateinit var bootDialogStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshStatus()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(0xFFF7F7F7.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val title = TextView(this).apply {
            text = "Volume Guard"
            textSize = 25f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF202020.toInt())
        }
        content.addView(title)
        content.addView(text("Mantém o volume de mídia no valor escolhido e pode bloquear a tela automaticamente.", 14))
        status = text("Status: parado", 14)
        content.addView(status)

        content.addView(section("Controle de volume"))
        volumeValue = text("Volume alvo: ${Prefs.volume(this)}%", 16)
        content.addView(volumeValue)
        volumeSeek = SeekBar(this).apply {
            max = 100
            progress = Prefs.volume(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    volumeValue.text = "Volume alvo: $progress%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    Prefs.setVolume(this@MainActivity, volumeSeek.progress)
                    refreshStatus()
                }
            })
        }
        content.addView(volumeSeek)
        content.addView(text("Intervalo de ajuste (segundos; padrão 60)", 14))
        volumeInterval = numberInput(Prefs.volumeInterval(this).toString())
        content.addView(volumeInterval)

        content.addView(section("Recuperação alternativa (opcional)"))
        content.addView(text(
            "O ajuste normal pela API continua sendo usado primeiro, com até cinco tentativas. Só se o Android não aplicar o volume, o app poderá acordar a tela, abrir as configurações de som e tocar nas duas posições configuradas abaixo. A recuperação procura a tela de som nas janelas acessíveis e pode repetir o toque na barra até cinco vezes. Requer ativar manualmente o serviço de acessibilidade.",
            13
        ))
        fallbackSwitch = Switch(this).apply {
            text = "Tentar recuperação pela tela se o volume falhar"
            isChecked = Prefs.uiFallbackEnabled(this@MainActivity)
            setTextColor(0xFF202020.toInt())
            setOnCheckedChangeListener { _, checked ->
                Prefs.setUiFallbackEnabled(this@MainActivity, checked)
                if (checked && !Prefs.accessibilityServiceEnabled(this@MainActivity)) {
                    Toast.makeText(
                        this@MainActivity,
                        "Ative o serviço de acessibilidade do Volume Guard antes de usar a recuperação alternativa.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                refreshStatus()
            }
        }
        content.addView(fallbackSwitch)
        content.addView(text("Tempo entre cada etapa da recuperação (segundos; padrão 2; permitido 1–10)", 14))
        fallbackStepDelay = numberInput(Prefs.fallbackStepDelaySeconds(this).toString())
        content.addView(fallbackStepDelay)
        content.addView(text(
            "Depois do comando para abrir as configurações, o app espera esse tempo para acender a tela; espera o mesmo tempo após acordar para tocar na barra; e, se reconhecer o aviso, espera novamente antes de tocar em OK.",
            12
        ))
        content.addView(button("Ativar serviço de acessibilidade") { openAccessibilitySettings() })
        content.addView(button("Calibrar posição da barra Media volume") { startCalibration(Prefs.TARGET_SLIDER) })
        content.addView(button("Calibrar posição do OK do aviso") { startCalibration(Prefs.TARGET_OK) })
        portraitSwitch = Switch(this).apply {
            text = "Forçar orientação retrato durante a recuperação"
            isChecked = Prefs.forcePortraitDuringFallback(this@MainActivity)
            setTextColor(0xFF202020.toInt())
            setOnCheckedChangeListener { _, checked ->
                Prefs.setForcePortraitDuringFallback(this@MainActivity, checked)
                if (checked && !Settings.System.canWrite(this@MainActivity)) {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Permitir mudar orientação")
                        .setMessage("Para girar temporariamente o celular para retrato e depois restaurar a orientação anterior, permita que o Volume Guard modifique as configurações do sistema.")
                        .setPositiveButton("Abrir permissão") { _, _ -> openWriteSettingsPermission() }
                        .setNegativeButton("Agora não", null)
                        .show()
                }
                refreshStatus()
            }
        }
        content.addView(portraitSwitch)
        content.addView(button("Permitir alteração temporária da orientação") { openWriteSettingsPermission() })
        portraitStatus = text("", 12)
        content.addView(portraitStatus)
        content.addView(text(
            "A tela dividida é detectada automaticamente durante a recuperação. O Volume Guard tenta expandir as Configurações para tela cheia antes dos toques. Se o Android/ROM não permitir sair da divisão, ele cancela o toque para evitar usar coordenadas erradas. Ao terminar, tenta voltar para a tela anterior; a restauração exata do par dividido depende do sistema.",
            12
        ))
        fallbackStatus = text("", 13)
        content.addView(fallbackStatus)
        content.addView(text(
            "Como calibrar: deixe o celular em retrato. Na calibração da barra, arraste a bolinha até a posição da barra que corresponde ao volume-alvo e salve. Na calibração do OK, toque manualmente na barra para mostrar o aviso, arraste a bolinha sobre OK e salve. O aviso só é tocado automaticamente quando o serviço reconhece um diálogo compatível com aviso de volume.",
            12
        ))

        content.addView(section("Desligar a tela"))
        screenSwitch = Switch(this).apply {
            text = "Ativar bloqueio automático da tela"
            isChecked = Prefs.screenEnabled(this@MainActivity)
            setTextColor(0xFF202020.toInt())
            setOnCheckedChangeListener { _, checked ->
                Prefs.setScreenEnabled(this@MainActivity, checked)
                if (checked && !isAdminActive()) showAdminHelp()
                restartServiceIfRunning()
            }
        }
        content.addView(screenSwitch)
        content.addView(text("Tempo antes de bloquear (segundos; padrão 15)", 14))
        screenInterval = numberInput(Prefs.screenInterval(this).toString())
        content.addView(screenInterval)
        adminStatus = text("", 14)
        content.addView(adminStatus)
        content.addView(button("Solicitar permissão para bloquear a tela") { requestAdminPermission() })
        content.addView(button("Instruções manuais para ativar a permissão") { showAdminHelp() })

        content.addView(section("Inicialização"))
        bootSwitch = Switch(this).apply {
            text = "Iniciar automaticamente após reiniciar o celular"
            isChecked = Prefs.startOnBoot(this@MainActivity)
            setTextColor(0xFF202020.toInt())
            setOnCheckedChangeListener { _, checked ->
                Prefs.setStartOnBoot(this@MainActivity, checked)
                if (!checked && ::bootDialogSwitch.isInitialized) {
                    bootDialogSwitch.isChecked = false
                    bootDialogSwitch.isEnabled = false
                    Prefs.setBootTapEnabled(this@MainActivity, false)
                } else if (checked && ::bootDialogSwitch.isInitialized) {
                    bootDialogSwitch.isEnabled = true
                }
                if (checked) Toast.makeText(
                    this@MainActivity,
                    "Se o aparelho bloquear a inicialização automática, permita-a nas configurações do fabricante.",
                    Toast.LENGTH_LONG
                ).show()
                refreshStatus()
            }
        }
        content.addView(bootSwitch)
        content.addView(text("A inicialização automática depende do fabricante. Alguns aparelhos exigem permitir 'Início automático' e retirar o app da otimização de bateria.", 13))
        bootDialogSwitch = Switch(this).apply {
            text = "Tocar automaticamente no OK do diálogo da ROM ao iniciar"
            isChecked = Prefs.bootTapEnabled(this@MainActivity)
            isEnabled = Prefs.startOnBoot(this@MainActivity)
            setTextColor(0xFF202020.toInt())
            setOnCheckedChangeListener { _, checked ->
                if (checked && !Prefs.startOnBoot(this@MainActivity)) {
                    isChecked = false
                    Toast.makeText(this@MainActivity, "Ative primeiro a inicialização automática do Volume Guard.", Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }
                Prefs.setBootTapEnabled(this@MainActivity, checked)
                if (checked && !Prefs.hasBootOkPosition(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Calibre primeiro a posição do botão OK do diálogo de inicialização.", Toast.LENGTH_LONG).show()
                }
                refreshStatus()
            }
        }
        content.addView(bootDialogSwitch)
        content.addView(button("Armar calibração do OK da inicialização") { startCalibration(Prefs.TARGET_BOOT_OK) })
        bootDialogStatus = text("", 12)
        content.addView(bootDialogStatus)
        content.addView(text("Para calibrar: toque em Armar calibração, reinicie o celular e, quando o diálogo da ROM aparecer, arraste a bolinha até o botão OK e salve. O toque automático só é armado quando esta opção e 'Iniciar automaticamente' estiverem ativadas.", 12))

        content.addView(section("Serviço"))
        content.addView(button("Salvar configurações") {
            saveFields()
            restartServiceIfRunning()
            Toast.makeText(this, "Configurações salvas.", Toast.LENGTH_SHORT).show()
            refreshStatus()
        })
        content.addView(button("Iniciar serviço") {
            saveFields()
            Prefs.setRunning(this, true)
            try {
                val i = Intent(this, VolumeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
                Toast.makeText(this, "Serviço iniciado.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Prefs.setRunning(this, false)
                Toast.makeText(this, "Não foi possível iniciar o serviço: ${e.message}", Toast.LENGTH_LONG).show()
            }
            refreshStatus()
        })
        content.addView(button("Parar serviço") {
            Prefs.setRunning(this, false)
            stopService(Intent(this, VolumeService::class.java))
            Toast.makeText(this, "Serviço parado.", Toast.LENGTH_SHORT).show()
            refreshStatus()
        })

        content.addView(section("Permissões e compatibilidade"))
        content.addView(text(
            "• Para bloquear a tela, ative Volume Guard como administrador do dispositivo. O app usa essa permissão apenas para bloquear a tela.\n\n" +
                "• Se o pedido automático falhar: Configurações → Segurança (ou Segurança e privacidade) → Apps de administrador do dispositivo / Administradores do dispositivo → Volume Guard → Ativar.\n\n" +
                "• Para a recuperação alternativa, ative manualmente Volume Guard em Configurações → Acessibilidade. Este serviço usa gestos para calibrar os pontos e para as tentativas de recuperação ativadas por você.\n\n" +
                "• Para forçar retrato temporariamente durante a recuperação, conceda a permissão especial de modificar configurações do sistema. A orientação original será restaurada ao terminar a tentativa. Se a tela estiver dividida, o app detecta o divisor e tenta sair do modo dividido antes do toque; o Android/ROM pode não restaurar exatamente o mesmo par de apps ao voltar.\n\n" +
                "• O toque no diálogo de inicialização usa uma terceira posição e só fica armado quando 'Iniciar automaticamente' também está ligado. Ele exige a acessibilidade ativa e um diálogo com botão OK reconhecível. O app tenta novamente detectar o diálogo durante os primeiros minutos após a inicialização.\n\n" +
                "• Para funcionar em segundo plano: Configurações → Aplicativos → Volume Guard → Bateria → 'Não otimizar', 'Sem restrições' ou 'Permitir atividade em segundo plano'.\n\n" +
                "• Em alguns aparelhos, a tela de som pode ter um layout diferente. As posições salvas podem precisar de nova calibração após mudança de resolução, rotação ou atualização do sistema.\n\n" +
                "• O Android pode continuar limitando o volume por segurança. A recuperação alternativa é uma tentativa e não garante ultrapassar limites impostos pelo sistema.\n\n" +
                "• A opção de iniciar após reinicialização só funciona depois que o usuário abrir o app pelo menos uma vez após instalar. Alguns fabricantes bloqueiam esse comportamento.",
            13
        ))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun startCalibration(target: String) {
        if (!Prefs.accessibilityServiceEnabled(this)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Ativar acessibilidade")
                .setMessage("Para posicionar a bolinha e executar toques, ative manualmente o serviço Volume Guard nas configurações de Acessibilidade. Depois volte a este app e toque novamente no botão de calibração.")
                .setPositiveButton("Abrir acessibilidade") { _, _ -> openAccessibilitySettings() }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        Prefs.setCalibrationTarget(this, target)
        Prefs.setCalibrationActive(this, true)
        if (target == Prefs.TARGET_BOOT_OK) {
            Toast.makeText(
                this,
                "Calibração armada. Reinicie o celular; quando o diálogo da ROM aparecer, posicione a bolinha sobre OK e salve.",
                Toast.LENGTH_LONG
            ).show()
            moveTaskToBack(true)
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
        } catch (_: Exception) {
            Prefs.setCalibrationActive(this, false)
            Prefs.setCalibrationTarget(this, Prefs.TARGET_NONE)
            Toast.makeText(this, "Não foi possível abrir as configurações de som neste aparelho.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openWriteSettingsPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Não foi possível abrir a permissão de modificar configurações.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun saveFields() {
        Prefs.setVolume(this, volumeSeek.progress)
        Prefs.setVolumeInterval(this, volumeInterval.text.toString().toIntOrNull()?.coerceIn(5, 86400) ?: 60)
        Prefs.setScreenInterval(this, screenInterval.text.toString().toIntOrNull()?.coerceIn(5, 3600) ?: 15)
        Prefs.setFallbackStepDelaySeconds(
            this,
            fallbackStepDelay.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 2
        )
        volumeInterval.setText(Prefs.volumeInterval(this).toString())
        screenInterval.setText(Prefs.screenInterval(this).toString())
        fallbackStepDelay.setText(Prefs.fallbackStepDelaySeconds(this).toString())
    }

    private fun restartServiceIfRunning() {
        if (!Prefs.running(this)) return
        stopService(Intent(this, VolumeService::class.java))
        val i = Intent(this, VolumeService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        } catch (_: Exception) {}
    }

    private fun isAdminActive(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, ScreenAdminReceiver::class.java))
    }

    private fun requestAdminPermission() {
        val component = ComponentName(this, ScreenAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Permite que o Volume Guard bloqueie a tela após o intervalo configurado. Não permite apagar dados nem controlar o aparelho.")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            showAdminHelp()
        }
    }

    private fun showAdminHelp() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Ativar permissão manualmente")
            .setMessage(
                "1. Abra Configurações do Android.\n" +
                    "2. Entre em Segurança ou Segurança e privacidade.\n" +
                    "3. Procure 'Apps de administrador do dispositivo', 'Administradores do dispositivo' ou 'Apps de administração'.\n" +
                    "4. Selecione Volume Guard e toque em Ativar.\n\n" +
                    "Se não encontrar, use a busca das Configurações por 'administrador do dispositivo'. O caminho muda conforme a marca e a versão do Android."
            )
            .setPositiveButton("Tentar abrir permissão") { _, _ -> requestAdminPermission() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (::adminStatus.isInitialized) refreshStatus()
    }

    private fun refreshStatus() {
        if (::status.isInitialized) status.text =
            if (Prefs.running(this)) "Status: serviço ativo (ou iniciando)" else "Status: parado"
        if (::adminStatus.isInitialized) adminStatus.text =
            if (isAdminActive()) "Permissão de bloqueio: ativada" else "Permissão de bloqueio: não ativada"
        if (::fallbackStatus.isInitialized) {
            val access = if (Prefs.accessibilityServiceEnabled(this)) "ativada" else "desativada"
            val slider = if (Prefs.hasSliderPosition(this)) "configurada" else "não configurada"
            val ok = if (Prefs.hasOkPosition(this)) "configurada" else "não configurada"
            fallbackStatus.text = "Acessibilidade: $access\nPosição da barra: $slider\nPosição do OK do aviso: $ok\nIntervalo entre etapas: ${Prefs.fallbackStepDelaySeconds(this)} s"
        }
        if (::portraitStatus.isInitialized) {
            portraitStatus.text = if (Settings.System.canWrite(this)) {
                "Permissão para modificar orientação: concedida"
            } else {
                "Permissão para modificar orientação: não concedida (em paisagem, o app tentará adaptar as coordenadas)"
            }
        }
        if (::bootDialogStatus.isInitialized) {
            val position = if (Prefs.hasBootOkPosition(this)) "configurada" else "não configurada"
            val enabled = if (Prefs.bootTapEligible(this)) "ativado" else "desativado (requer início automático)"
            bootDialogStatus.text = "Posição do OK de inicialização: $position\nToque ao iniciar: $enabled\nAcessibilidade: ${if (Prefs.accessibilityServiceEnabled(this)) "ativada" else "desativada"}"
        }
        if (::bootDialogSwitch.isInitialized && ::bootSwitch.isInitialized) {
            bootDialogSwitch.isEnabled = bootSwitch.isChecked
        }
    }

    private fun section(s: String) = TextView(this).apply {
        text = s
        textSize = 18f
        setTypeface(null, Typeface.BOLD)
        setTextColor(0xFF202020.toInt())
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun text(s: String, size: Int) = TextView(this).apply {
        text = s
        textSize = size.toFloat()
        setTextColor(0xFF333333.toInt())
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun numberInput(value: String) = EditText(this).apply {
        setSingleLine(true)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setText(value)
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
        isAllCaps = false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
