package com.winwasm64.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Activity для native видеофабрики на Media3 ExoPlayer.
 *
 * v4.64.0: запускается из VideoSearchSection вместо WebView-based VideoSearchActivity
 * для прямого воспроизведения найденных видео (VK/RuTube/YouTube).
 */
class NativeVideoPlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val videoUrl = intent.getStringExtra("url") ?: ""
        val title = intent.getStringExtra("title") ?: "Видео"
        val thumbnailUrl = intent.getStringExtra("thumbnail")
        val referer = intent.getStringExtra("referer")
        // v4.81.0: ключ библиотеки и позиция продолжения
        val libKey = intent.getStringExtra("libKey") ?: videoUrl
        val start = intent.getLongExtra("start", 0L)

        setContent {
            com.winwasm64.ui.theme.OmniTheme {
                NativeVideoPlayerScreen(
                    videoUrl = videoUrl,
                    title = title,
                    thumbnailUrl = thumbnailUrl,
                    referer = referer,
                    startPositionMs = start,
                    onProgress = { pos, dur ->
                        com.winwasm64.torrent.VideoLibrary.setProgress(applicationContext, libKey, pos, dur)
                    },
                    onBack = { finish() },
                    onComplete = { finish() }
                )
            }
        }
    }
}
