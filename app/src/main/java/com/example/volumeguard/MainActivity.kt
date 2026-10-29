package com.example.volumeguard

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import android.graphics.Typeface

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var volumeSeek: SeekBar
    private lateinit var volumeValue: TextView
    private lateinit var volumeInterval: EditText
    private lateinit var screenSwitch: Switch
    private lateinit var screenInterval: EditText
    private lateinit var bootSwitch: Switch
    private lateinit var adminStatus: TextView

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
                }
            })
        }
        content.addView(volumeSeek)
        content.addView(text("Intervalo de ajuste (segundos; padrão 60)", 14))
        volumeInterval = numberInput(Prefs.volumeInterval(this).toString())
        content.addView(volumeInterval)

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
                if (checked) Toast.makeText(this@MainActivity,
                    "Se o aparelho bloquear a inicialização automática, permita-a nas configurações do fabricante.",
                    Toast.LENGTH_LONG).show()
            }
        }
        content.addView(bootSwitch)
        content.addView(text("A inicialização automática depende do fabricante. Alguns aparelhos exigem permitir 'Início automático' e retirar o app da otimização de bateria.", 13))

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
            "• Os nomes variam: 'Apps de administração', 'Administradores do dispositivo', 'Permissões especiais' ou 'Segurança'. Use a busca nas Configurações.\n\n" +
            "• Para funcionar em segundo plano: Configurações → Aplicativos → Volume Guard → Bateria → 'Não otimizar', 'Sem restrições' ou 'Permitir atividade em segundo plano'.\n\n" +
            "• Em Xiaomi/Redmi/POCO, Huawei/Honor, Oppo/Realme e alguns Samsung, talvez seja necessário ativar 'Início automático' / 'Auto start' e permitir execução em segundo plano.\n\n" +
            "• O Android pode limitar o volume por segurança. Este app não tenta contornar limites de audição do sistema; se o volume for bloqueado, isso será indicado na notificação.\n\n" +
            "• A opção de iniciar após reinicialização só funciona depois que o usuário abrir o app pelo menos uma vez após instalar. Alguns fabricantes bloqueiam esse comportamento.",
            13
        ))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun saveFields() {
        Prefs.setVolume(this, volumeSeek.progress)
        Prefs.setVolumeInterval(this, volumeInterval.text.toString().toIntOrNull()?.coerceIn(5, 86400) ?: 60)
        Prefs.setScreenInterval(this, screenInterval.text.toString().toIntOrNull()?.coerceIn(5, 3600) ?: 15)
        volumeInterval.setText(Prefs.volumeInterval(this).toString())
        screenInterval.setText(Prefs.screenInterval(this).toString())
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
