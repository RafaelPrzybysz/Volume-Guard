package com.example.volumeguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // Arma a tentativa de toque somente quando as duas opções exigidas estão ativas.
                if (Prefs.bootTapEligible(context)) {
                    // Se ainda estiver em calibração, o ponto pode ser salvo no próprio diálogo
                    // desta inicialização; o serviço de acessibilidade tentará depois de salvar.
                    Prefs.setBootTapPendingAt(context, System.currentTimeMillis())
                    Prefs.setBootTapSentAt(context, 0L)
                    // Avisa o serviço de acessibilidade caso ele já esteja ativo e o diálogo
                    // da ROM tenha aparecido antes do evento de boot ser processado.
                    context.sendBroadcast(Intent(ACTION_BOOT_TAP_PENDING).setPackage(context.packageName))
                }
                if (Prefs.startOnBoot(context) && Prefs.running(context)) {
                    startVolumeService(context)
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (Prefs.startOnBoot(context) && Prefs.running(context)) {
                    startVolumeService(context)
                }
            }
        }
    }

    companion object {
        const val ACTION_BOOT_TAP_PENDING = "com.example.volumeguard.ACTION_BOOT_TAP_PENDING"
    }

    private fun startVolumeService(context: Context) {
        val serviceIntent = Intent(context, VolumeService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {
            // Alguns fabricantes bloqueiam o início automático; o usuário pode abrir o app.
        }
    }
}
