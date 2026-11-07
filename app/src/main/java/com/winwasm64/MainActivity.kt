package com.winwasm64

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.winwasm64.ui.MediaState
import com.winwasm64.ui.WindowsWasm64DesktopScreen
import com.winwasm64.ui.theme.OmniTheme
import com.winwasm64.vpn.BrowsecVpnService
import com.winwasm64.vpn.ModernTls

/** v4.4.0: адрес, пришедший в уже работающее приложение (onNewIntent) — экран браузера его подхватывает. */
object ExternalIntents {
    val pendingUrlState = androidx.compose.runtime.mutableStateOf<String?>(null)
    var pendingUrl: String?
        get() = pendingUrlState.value
        set(v) { pendingUrlState.value = v }
    /** v4.5.0: плитка быстрых настроек просит открыть раздел «Приложения» (нет разрешения на VPN или пустой список). */
    val openAppsRequest = androidx.compose.runtime.mutableIntStateOf(0)

    /** Из текста «Поделиться» вытаскиваем первую ссылку; если ссылки нет — возвращаем сам текст (уйдёт в поиск). */
    fun extractUrl(text: String?): String? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        val m = Regex("(?i)\\b(?:https?://|magnet:\\?)[^\\s<>\"']+").find(t)
        if (m != null) return m.value.trimEnd('.', ',', ')', ']')
        val bare = Regex("(?i)\\b(?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?:/[^\\s]*)?").find(t)
        if (bare != null && t.length < 200 && !t.contains(' ')) return "https://" + bare.value
        return t
    }
}

class MainActivity : ComponentActivity() {
    override fun onStop() {
        super.onStop()
        try { com.winwasm64.ui.MediaPositions.flush() } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModernTls.install(this)
        com.winwasm64.ui.MediaPositions.init(this)
        CrashCatcher.install(this)
        handleIncomingIntent(intent)
        registerPipReceiver()
        val initialUri = intent?.dataString
        setContent {
            OmniTheme {
                WindowsWasm64DesktopScreen(initialIntentUri = initialUri)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == BrowsecVpnService.ACTION_OPEN_SERVER_PICKER) {
            BrowsecVpnService.openServerPickerRequestCount++
        }
        // v4.4.0: ссылка из другого приложения / уведомления, когда браузер уже запущен (singleTask → onNewIntent)
        if (intent?.action == Intent.ACTION_VIEW) {
            val u = intent.dataString
            if (!u.isNullOrBlank()) ExternalIntents.pendingUrl = u
        }
        // v4.5.1: «Поделиться» ссылкой/текстом, системный поиск и «Найти в OmniBrowser» из выделенного текста
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            val u = ExternalIntents.extractUrl(text)
            if (!u.isNullOrBlank()) ExternalIntents.pendingUrl = u
        }
        if (intent?.action == Intent.ACTION_WEB_SEARCH) {
            val q = intent.getStringExtra(android.app.SearchManager.QUERY)
            if (!q.isNullOrBlank()) ExternalIntents.pendingUrl = ExternalIntents.extractUrl(q)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && intent?.action == Intent.ACTION_PROCESS_TEXT) {
            val q = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            if (!q.isNullOrBlank()) ExternalIntents.pendingUrl = ExternalIntents.extractUrl(q)
        }
        if (intent?.getBooleanExtra("omni_open_apps", false) == true) ExternalIntents.openAppsRequest.intValue++
    }

    // ===================== v4.2.0: картинка в картинке (Android 8.0+, изолировано от ART Android 5–7) =====================

    private fun pipSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PIP_CONTROL) return
            val cmd = intent.getStringExtra(EXTRA_PIP_CMD) ?: return
            MediaState.control(cmd)
        }
    }

    private fun registerPipReceiver() {
        if (!pipSupported()) return
        try {
            ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP_CONTROL), ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { unregisterReceiver(pipReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    /** Вызывается экраном, когда меняется состояние воспроизведения в активной вкладке. */
    fun updatePipParams(playing: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pipSupported()) {
            Api26Pip.update(this, playing)
        }
    }

    /** Свернуть в окно PiP вручную (кнопка в меню). */
    fun enterPipNow(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pipSupported()) {
            return Api26Pip.enter(this)
        }
        return false
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Пользователь уходит из приложения (Домой/недавние), а во вкладке играет видео — продолжаем в окне PiP
        if (MediaState.activeMediaPlaying && !MediaState.inPip) enterPipNow()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        MediaState.inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) updatePipParams(MediaState.activeMediaPlaying)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private object Api26Pip {
        private fun pipAction(act: Activity, code: Int, icon: Int, title: String, cmd: String): RemoteAction {
            val intent = Intent(ACTION_PIP_CONTROL).setPackage(act.packageName).putExtra(EXTRA_PIP_CMD, cmd)
            val pi = PendingIntent.getBroadcast(act, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return RemoteAction(Icon.createWithResource(act, icon), title, title, pi)
        }

        private fun pipActions(act: Activity, playing: Boolean): List<RemoteAction> {
            val toggle = if (playing) pipAction(act, 2, R.drawable.ic_pip_pause, "Пауза", "toggle")
            else pipAction(act, 2, R.drawable.ic_pip_play, "Воспроизвести", "toggle")
            val all = listOf(
                pipAction(act, 1, R.drawable.ic_pip_rewind, "Назад 10 с", "back"),
                toggle,
                pipAction(act, 3, R.drawable.ic_pip_forward, "Вперёд 10 с", "fwd")
            )
            val max = try { act.maxNumPictureInPictureActions } catch (_: Exception) { 3 }
            return if (max >= 3) all else listOf(toggle)
        }

        private fun buildParams(act: Activity, playing: Boolean): PictureInPictureParams {
            val b = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setActions(pipActions(act, playing))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Api31Pip.configure(b, playing)
            }
            return b.build()
        }

        fun update(act: Activity, playing: Boolean) {
            try { act.setPictureInPictureParams(buildParams(act, playing)) } catch (_: Throwable) {}
        }

        fun enter(act: Activity): Boolean =
            try { act.enterPictureInPictureMode(buildParams(act, true)) } catch (_: Throwable) { false }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31Pip {
        fun configure(b: PictureInPictureParams.Builder, playing: Boolean) {
            b.setAutoEnterEnabled(playing)
            b.setSeamlessResizeEnabled(true)
        }
    }

    companion object {
        const val ACTION_PIP_CONTROL = "com.winwasm64.PIP_CONTROL"
        const val EXTRA_PIP_CMD = "cmd"
    }
}
