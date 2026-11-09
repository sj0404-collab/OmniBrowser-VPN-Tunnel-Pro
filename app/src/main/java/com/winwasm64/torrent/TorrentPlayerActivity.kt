package com.winwasm64.torrent

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView

/**
 * v4.41.0: встроенный плеер «смотреть/слушать из раздачи».
 *
 * Открывается кнопкой «▶ Смотреть» в результатах поиска. Куски качаются по порядку, локальный сервер
 * отдаёт их по HTTP, VideoView играет — без ожидания полной загрузки. Аудио играет так же (VideoView
 * справляется с mp3/flac/m4a через системный декодер).
 *
 * Если пиров нет — экран говорит это прямо, а не крутит «загрузка…» вечно.
 */
class TorrentPlayerActivity : Activity() {

    private lateinit var video: VideoView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var engine: BitTorrentEngine? = null
    private var prepared = false

    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("name").orEmpty()
        val magnet = intent.getStringExtra("magnet").orEmpty().ifBlank { null }
        val torrentUrl = intent.getStringExtra("torrentUrl").orEmpty().ifBlank { null }
        val referer = intent.getStringExtra("referer").orEmpty().ifBlank { null }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0F12"))
            setPadding(24, 32, 24, 24)
        }
        val title = TextView(this).apply {
            text = name.ifBlank { "Воспроизведение из раздачи" }
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        }
        root.addView(title)

        status = TextView(this).apply {
            text = "Готовлю поток…"
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 12f
            setPadding(0, 12, 0, 12)
        }
        root.addView(status)

        video = VideoView(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(video)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }
        controls.addView(Button(this).apply {
            text = "⏹ Остановить"
            setOnClickListener {
                TorrentStreamer.stop()
                TorrentStreamServer.stop()
                finish()
            }
        })
        controls.addView(Button(this).apply {
            text = "⏯ Пауза"
            setOnClickListener {
                if (video.isPlaying) video.pause() else video.start()
            }
        })
        root.addView(controls)
        setContentView(root)

        Thread({
            val engine = BitTorrentEngine(applicationContext)
            this.engine = engine
            val dir = java.io.File(cacheDir, "stream").apply { mkdirs() }
            val ok = TorrentStreamer.start(engine, magnet, torrentUrl, referer, dir)
            if (!ok) {
                handler.post { status.text = TorrentStreamer.statusText }
                return@Thread
            }
            val url = TorrentStreamServer.start()
            if (url == null) {
                handler.post { status.text = "Не удалось поднять локальный сервер потока" }
                return@Thread
            }
            // ждём, пока появится первый мегабайт — иначе плеер сразу покажет ошибку
            val deadline = System.currentTimeMillis() + 40_000L
            while (System.currentTimeMillis() < deadline &&
                TorrentStreamer.availableBytes < 1_000_000L &&
                TorrentStreamer.isRunning()
            ) {
                Thread.sleep(300)
            }
            handler.post {
                if (TorrentStreamer.availableBytes <= 0L) {
                    status.text = TorrentStreamer.statusText
                    return@post
                }
                prepared = true
                video.setMediaController(MediaController(this))
                video.setVideoPath(url)
                video.setOnPreparedListener { mp ->
                    mp.isLooping = false
                    video.start()
                    status.text = "Играет из раздачи · ${TorrentStreamer.fileName}"
                }
                video.setOnErrorListener { _, what, extra ->
                    status.text = "Плеер не смог открыть поток (код $what/$extra). Попробуйте другой результат."
                    true
                }
            }
        }, "StreamPrepare").also { it.isDaemon = true; it.start() }

        handler.post(ticker)
    }

    private fun updateStatus() {
        if (!prepared) {
            status.text = TorrentStreamer.statusText
            return
        }
        val have = BitTorrentStreamerFormatting.format(BitTorrentStreamerHolder.available())
        val total = BitTorrentStreamerFormatting.format(TorrentStreamer.totalBytes)
        val speed = BitTorrentStreamerFormatting.formatSpeed(TorrentStreamer.speedBytesPerSec)
        status.text = "${TorrentStreamer.statusText}\n$have из $total · ↓ $speed"
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        TorrentStreamer.stop()
        TorrentStreamServer.stop()
        super.onDestroy()
    }
}

/** Мелочи форматирования, чтобы не тянуть их из Compose. */
internal object BitTorrentStreamerFormatting {
    fun format(bytes: Long): String = BitTorrentEngine.formatSize(bytes)
    fun formatSpeed(bytesPerSec: Long): String = BitTorrentEngine.formatSpeed(bytesPerSec)
}

internal object BitTorrentStreamerHolder {
    fun available(): Long = TorrentStreamer.availableBytes
}
