package com.winwasm64

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.winwasm64.ui.MediaState
import com.winwasm64.ui.WindowsWasm64DesktopScreen
import com.winwasm64.ui.theme.OmniTheme
import com.winwasm64.vpn.BrowsecVpnService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
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
    }

    // ===================== v4.2.0: картинка в картинке =====================

    private fun pipSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(autoEnter)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
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
    }
}
