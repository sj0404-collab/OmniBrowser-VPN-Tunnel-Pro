package com.winwasm64

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.winwasm64.ui.MediaState
import com.winwasm64.ui.WindowsWasm64DesktopScreen
import com.winwasm64.ui.theme.OmniTheme
import com.winwasm64.vpn.BrowsecVpnService

/** v4.4.0: адрес, пришедший в уже работающее приложение (onNewIntent) — экран браузера его подхватывает. */
object ExternalIntents {
    val pendingUrlState = androidx.compose.runtime.mutableStateOf<String?>(null)
    var pendingUrl: String?
        get() = pendingUrlState.value
        set(v) { pendingUrlState.value = v }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    }

    // ===================== v4.2.0: картинка в картинке =====================

    private fun pipSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(playing: Boolean): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.setActions(pipActions(playing))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(playing)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    // v4.2.3: кнопки в окне PiP — «−10 с», «пауза/пуск», «+10 с» (как у нативных видеоплееров)
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun pipAction(code: Int, icon: Int, title: String, cmd: String): RemoteAction {
        val intent = Intent(ACTION_PIP_CONTROL).setPackage(packageName).putExtra(EXTRA_PIP_CMD, cmd)
        val pi = PendingIntent.getBroadcast(this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return RemoteAction(Icon.createWithResource(this, icon), title, title, pi)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun pipActions(playing: Boolean): List<RemoteAction> {
        // v4.2.5: кнопка действует по фактическому состоянию видео в момент нажатия (значок может чуть отставать)
        val toggle = if (playing) pipAction(2, R.drawable.ic_pip_pause, "Пауза", "toggle")
        else pipAction(2, R.drawable.ic_pip_play, "Воспроизвести", "toggle")
        val all = listOf(
            pipAction(1, R.drawable.ic_pip_rewind, "Назад 10 с", "back"),
            toggle,
            pipAction(3, R.drawable.ic_pip_forward, "Вперёд 10 с", "fwd")
        )
        val max = try { maxNumPictureInPictureActions } catch (_: Exception) { 3 }
        return if (max >= 3) all else listOf(toggle)
    }

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
        if (!pipSupported()) return
        try { setPictureInPictureParams(pipParams(playing)) } catch (_: Exception) {}
    }

    /** Свернуть в окно PiP вручную (кнопка в меню). */
    fun enterPipNow(): Boolean {
        if (!pipSupported()) return false
        return try { enterPictureInPictureMode(pipParams(true)) } catch (_: Exception) { false }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Пользователь уходит из приложения (Домой/недавние), а во вкладке играет видео — продолжаем в окне PiP
        if (MediaState.activeMediaPlaying && !MediaState.inPip) enterPipNow()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        MediaState.inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) updatePipParams(MediaState.activeMediaPlaying)
    }

    companion object {
        const val ACTION_PIP_CONTROL = "com.winwasm64.PIP_CONTROL"
        const val EXTRA_PIP_CMD = "cmd"
    }
}
