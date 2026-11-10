package com.winwasm64.torrent

import android.app.Activity
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import java.io.File

/**
 * v4.42.0: встроенный плеер «смотреть/слушать из раздачи».
 *
 * Куски качаются по порядку, локальный сервер отдаёт их по HTTP (с Range), VideoView играет.
 * Если в раздаче несколько файлов — сначала показывается список: как в торрент-клиенте, выбираешь
 * один файл и качается только он, а не весь сезон.
 *
 * В статусе видно, каким КАНАЛОМ идёт трафик (Browsec / резерв / напрямую) — чтобы не гадать.
 */
class TorrentPlayerActivity : Activity() {

    private lateinit var video: VideoView
    private lateinit var status: TextView
    /** v4.47.1: кнопка «найти живую копию» — появляется, когда раздача не отдаёт данные. */
    private var retryButton: Button? = null
    private var titleText: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private var prepared = false

    private var magnetArg: String? = null
    private var torrentUrlArg: String? = null
    private var refererArg: String? = null
    private var preferredFile: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        magnetArg = intent.getStringExtra("magnet").orEmpty().ifBlank { null }
        torrentUrlArg = intent.getStringExtra("torrentUrl").orEmpty().ifBlank { null }
        refererArg = intent.getStringExtra("referer").orEmpty().ifBlank { null }
        preferredFile = savedInstanceState?.getString("preferredFile")
        buildView(intent.getStringExtra("name").orEmpty())
        startStream()
        handler.post(ticker)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        preferredFile?.let { outState.putString("preferredFile", it) }
    }

    private fun buildView(titleText: String) {
        this.titleText = titleText
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0F12"))
            setPadding(24, 32, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = titleText.ifBlank { "Воспроизведение из раздачи" }
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        })
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
            setOnClickListener { if (video.isPlaying) video.pause() else video.start() }
        })
        retryButton = Button(this).apply {
            text = "🔁 Найти другую раздачу"
            visibility = android.view.View.GONE
            setOnClickListener { findAnotherCopy() }
        }
        controls.addView(retryButton)
        controls.addView(Button(this).apply {
            text = "📂 Файлы"
            setOnClickListener {
                val all = TorrentStreamer.files
                if (all.size > 1) showFilePicker(all) else status.text = "В этой раздаче один файл"
            }
        })
        root.addView(controls)
        setContentView(root)
    }

    /** Запуск подготовки потока в фоне (метаданные, пиры, сервер). */
    private fun startStream() {
        prepared = false
        Thread({
            val engine = BitTorrentEngine(applicationContext)
            val dir = File(cacheDir, "stream").apply { mkdirs() }
            val ok = TorrentStreamer.start(engine, magnetArg, torrentUrlArg, refererArg, dir, preferredFile)
            if (!ok) {
                handler.post {
                    status.text = TorrentStreamer.statusText
                    // v4.47.1: правило «мёртвую раздачу не качать» — предлагаем найти живую копию сами
                    retryButton?.visibility = android.view.View.VISIBLE
                }
                return@Thread
            }
            val all = TorrentStreamer.files
            if (preferredFile == null && all.size > 1) {
                handler.post { showFilePicker(all) }
                return@Thread
            }
            val url = TorrentStreamServer.start()
            if (url == null) {
                handler.post { status.text = "Не удалось поднять локальный сервер потока" }
                return@Thread
            }
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
                if (!TorrentStreamer.isMedia(TorrentStreamer.fileName)) {
                    status.text = "«${TorrentStreamer.fileName}» — не видео и не аудио, плеер покажет чёрный экран. Нажмите «📂 Файлы» и выберите фильм или трек."
                }
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
    }

    /**
     * v4.47.1: раздача не отдаёт данные (нет метаданных / нет пиров) — ищем ту же вещь с сидами
     * и запускаем её. Это и есть правило «мёртвую раздачу не качать».
     */
    private fun findAnotherCopy() {
        val query = titleText.ifBlank { status.text.toString() }
        status.text = "🔁 Ищу другую раздачу с сидами: «${query.take(50)}»…"
        val latch = java.util.concurrent.CountDownLatch(1)
        val best = arrayOfNulls<CatalogTorrentItem>(1)
        Thread({
            val engine = BitTorrentEngine(applicationContext)
            engine.searchPublicTorrents(query) { res ->
                best[0] = res.filter {
                    (it.magnetUri.isNotBlank() || it.torrentUrl.isNotBlank()) && it.seeders > 0
                }.maxByOrNull { it.seeders }
                latch.countDown()
            }
        }, "FindAnother").also { it.isDaemon = true; it.start() }
        Thread({
            val finished = latch.await(70, java.util.concurrent.TimeUnit.SECONDS)
            val pick = best[0]
            handler.post {
                if (!finished || pick == null) {
                    status.text = "Живой копии с сидами не нашлось. Попробуйте другое название или вкладку «📦 Файлы»."
                    return@post
                }
                magnetArg = pick.magnetUri.ifBlank { null }
                torrentUrlArg = pick.torrentUrl.ifBlank { null }
                refererArg = pick.referer.ifBlank { null }
                preferredFile = null
                TorrentStreamer.stop()
                TorrentStreamServer.stop()
                buildView(pick.title)
                startStream()
                handler.post(ticker)
            }
        }, "FindAnotherWait").also { it.isDaemon = true; it.start() }
    }

    /** Список файлов раздачи: выбрать один — остальное даже не будет качаться. */
    private fun showFilePicker(files: List<TorrentFileEntry>) {
        TorrentStreamer.stop()
        TorrentStreamServer.stop()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#0E0F12"))
        }
        box.addView(TextView(this).apply {
            text = "В раздаче ${files.size} файлов — что открыть?"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 12)
        })
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // v4.46.0: видео/аудио — первыми и с иконкой; текущий файл помечен галочкой,
        // чтобы было видно, что именно играет (раньше список выглядел «не обновляющимся»).
        val sorted = files.sortedWith(
            compareByDescending<TorrentFileEntry> { TorrentStreamer.isMedia(it.relativePath) }
                .thenByDescending { it.length }
        )
        sorted.take(120).forEach { f ->
            val media = TorrentStreamer.isMedia(f.relativePath)
            val mark = if (f.relativePath == preferredFile) "✔ " else if (media) (if (f.relativePath.contains('.')) "🎬 " else "") else "📄 "
            list.addView(Button(this).apply {
                text = "$mark${f.relativePath.substringAfterLast('/')}  ·  ${BitTorrentEngine.formatSize(f.length)}"
                textSize = 12f
                setOnClickListener {
                    preferredFile = f.relativePath
                    buildView(intent.getStringExtra("name").orEmpty())
                    startStream()
                }
            })
        }
        scroll.addView(list)
        box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(box)
    }

    private fun updateStatus() {
        if (!prepared) {
            status.text = TorrentStreamer.statusText
            return
        }
        val have = BitTorrentEngine.formatSize(TorrentStreamer.availableBytes)
        val total = BitTorrentEngine.formatSize(TorrentStreamer.totalBytes)
        val speed = BitTorrentEngine.formatSpeed(TorrentStreamer.speedBytesPerSec)
        val channel = com.winwasm64.vpn.TorrentTunnel.lastChannel
        val format = com.winwasm64.torrent.TorrentSearchAgent.formatLabel(
            com.winwasm64.torrent.TorrentSearchAgent.Found(name = TorrentStreamer.fileName)
                .let { f ->
                    f.quality = com.winwasm64.torrent.TorrentSearchAgent.qualityOf(TorrentStreamer.fileName)
                    f.codec = com.winwasm64.torrent.TorrentSearchAgent.codecOf(TorrentStreamer.fileName)
                    f.audio = com.winwasm64.torrent.TorrentSearchAgent.audioOf(TorrentStreamer.fileName)
                    f
                }
        )
        status.text = "${TorrentStreamer.statusText}\n$have из $total · ↓ $speed · канал: $channel" +
            (if (format.isNotBlank()) "\nформат: $format" else "")
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        TorrentStreamer.stop()
        TorrentStreamServer.stop()
        super.onDestroy()
    }
}
