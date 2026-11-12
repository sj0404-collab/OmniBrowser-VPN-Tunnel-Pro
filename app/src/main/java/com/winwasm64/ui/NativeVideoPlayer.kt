package com.winwasm64.ui

import android.app.PictureInPictureParams
import android.content.Context
import android.os.Build
import android.util.Rational
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.winwasm64.ui.browser.OmniIcons
import com.winwasm64.ui.theme.OmniTheme

/**
 * Native видеофабрика на Media3 ExoPlayer — альтернатива WebView-based VideoSearchActivity.
 *
 * v4.64.0: из RupoopFork перенесён native player с поддержкой HLS/DASH, PiP и фоновым воспроизведением.
 *
 * @param videoUrl URL видео (mp4/m3u8/webm)
 * @param title Заголовок видео
 * @param thumbnailUrl Обложка (optional)
 */
@OptIn(androidx.media3.common.util.UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NativeVideoPlayerScreen(
    videoUrl: String,
    title: String,
    thumbnailUrl: String? = null,
    /** v4.66.0: откуда пришёл поток — VK/RuTube/YouTube отдают 403 без Referer. */
    referer: String? = null,
    onBack: () -> Unit = {},
    onComplete: () -> Unit = {}
) {
    val context = LocalContext.current

    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }

    // Создаём ExoPlayer
    LaunchedEffect(videoUrl, referer) {
        // v4.66.0: браузерные заголовки — без них прямые ссылки VK/RuTube отдают 403
        val ref = referer?.takeIf { it.isNotBlank() } ?: defaultReferer(videoUrl)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to ref, "Origin" to ref.trimEnd('/')))
        val dsFactory = DefaultDataSource.Factory(context, http)
        val exoPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
            .build()
        exoPlayer.setMediaItem(MediaItem.fromUri(videoUrl))
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF)
        player = exoPlayer
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                isPlaying = playWhenReady
            }

            override fun onPlayerStateChanged(playWhenReady: Boolean, playerState: Int) {
                if (playerState == Player.STATE_ENDED) {
                    onComplete()
                }
            }
        })
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // PiP support
    fun enterPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val activity = context as? android.app.Activity
            if (activity != null) {
                try {
                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        activity.enterPictureInPictureMode(params)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    OmniTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, color = Color.White, fontWeight = FontWeight.Medium) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(OmniIcons.Back, null, tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White
                    )
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.Black)
            ) {
                player?.let { p ->
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).also { pv ->
                                pv.player = p
                                pv.useController = true
                                pv.setOnClickListener {
                                    showControls = !showControls
                                    pv.useController = showControls
                                }
                            }
                        },
                        update = { _ -> }
                    )
                }

                // PiP button
                IconButton(
                    onClick = { enterPiP() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = OmniIcons.Desktop,
                        contentDescription = "Picture in Picture",
                        tint = Color.White
                    )
                }
            }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }
}

/** v4.66.0: откуда пришёл поток — по этому адресу ставим Referer/Origin. */
private fun defaultReferer(url: String): String {
    val host = try {
        java.net.URI(url).host?.lowercase().orEmpty()
    } catch (_: Throwable) {
        ""
    }
    return when {
        host.contains("vk") || host.contains("userapi") -> "https://vk.com/"
        host.contains("rutube") -> "https://rutube.ru/"
        host.contains("ok.ru") || host.contains("mycdn") -> "https://ok.ru/"
        host.contains("googlevideo") || host.contains("youtube") -> "https://www.youtube.com/"
        host.contains("dailymotion") -> "https://www.dailymotion.com/"
        host.isBlank() -> "https://www.youtube.com/"
        else -> "https://$host/"
    }
}

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/122.0.0.0 Mobile Safari/537.36"
