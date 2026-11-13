package com.winwasm64.fork

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.winwasm64.CrashCatcher
import com.winwasm64.ExternalIntents
import com.winwasm64.ui.MediaPositions
import com.winwasm64.ui.WindowsWasm64DesktopScreen
import com.winwasm64.ui.requestSection
import com.winwasm64.ui.theme.OmniTheme

/**
 * v4.85.0: ВТОРОЕ приложение внутри того же APK — «Omnibrowser Fork» (ядро RuppopFork).
 *
 * Ставится вместе с основным: один APK, один пакет, но в системе это отдельное приложение —
 * свой ярлык, своя задача (taskAffinity), своё имя. Открывается сразу в движке
 * (торренты / файлы / манга), без браузерной части, и перехватывает magnet и .torrent.
 *
 * Данные общие с основным приложением (одно хранилище настроек и загрузок), поэтому
 * скачанное и подписки видны из обоих.
 */
class ForkActivity : ComponentActivity() {

    override fun onStop() {
        super.onStop()
        try { MediaPositions.flush() } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MediaPositions.init(this)
        CrashCatcher.install(this)

        val incoming = intent?.dataString
        if (intent?.action == Intent.ACTION_VIEW && !incoming.isNullOrBlank()) {
            ExternalIntents.pendingUrl = incoming
        }
        // 1 = «Торрент» — сердце форка
        requestSection(1)

        setContent {
            OmniTheme {
                WindowsWasm64DesktopScreen(initialIntentUri = incoming)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val u = intent.dataString
        if (intent.action == Intent.ACTION_VIEW && !u.isNullOrBlank()) ExternalIntents.pendingUrl = u
    }
}
