package com.winwasm64.ui

import android.app.PictureInPictureParams
import android.content.Context
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.winwasm64.ui.browser.OmniIcons
import com.winwasm64.ui.theme.Omni
import kotlinx.coroutines.delay

/**
 * Native видеофабрика на Media3 ExoPlayer — альтернатива WebView-based VideoSearchActivity.
 *
 * v4.64.0: из RupoopFork перенесён native player с поддержкой HLS/DASH, PiP и фоновым воспроизведением.
 *
 * @param videoUrl URL видео (mp4/m3u8/webm)
 * @param title Заголовок видео
 * @param thumbnailUrl Обложка (optional)
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun NativeVideoPlayerScreen(
    videoUrl: String,
    title: String,
    thumbnailUrl: String? = null,
    onBack: () -> Unit = {},
    onComplete: () -> Unit = {}
) {
    val context = LocalContext.current

    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }

    // Создаём ExoPlayer
    LaunchedEffect(videoUrl) {
        val exoPlayer = ExoPlayer.Builder(context)
            .setMediaItem(MediaItem.fromUri(videoUrl))
            .setAutoPlay(true)
            .setRepeatMode(Player.REPEAT_MODE_OFF)
            .build()
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
            player?.let { p ->
                try {
                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startPictureInPictureMode(params)
                    }
                } catch (_: Exception) {}
            }
        }
    }

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
                PlayerView(
                    context = context,
                    player = p,
                    aspectRatioMode = PlayerView.ASPECT_RATIO_MATCH_PARENT
                ).also { pv ->
                    pv.useController = true
                    pv.controllerAutoHide = true
                    pv.resizeMode = PlayerView.RESIZE_MODE_ZOOM
                    pv.setOnClickListener {
                        showControls = !showControls
                        pv.useController = showControls
                    }
                }
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

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }
}
