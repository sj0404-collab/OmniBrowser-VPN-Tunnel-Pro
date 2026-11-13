package com.winwasm64.fork

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/** v4.86.0: результат установки второго приложения показываем человеку, а не молчим. */
class ForkInstallReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_PKG = "pkg"
        var lastResult = androidx.compose.runtime.mutableStateOf("")
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val text = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "RupoopFork установлен"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                }
                "Подтвердите установку RupoopFork"
            }
            else -> "Установка не удалась: " + msg.take(120)
        }
        lastResult.value = text
        runCatching { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
