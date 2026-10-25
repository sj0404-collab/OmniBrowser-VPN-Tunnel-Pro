package com.winwasm64.ui.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.winwasm64.vpn.VpnLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v4.3.0: фоновые загрузки видео/аудио из браузера (foreground service, уведомление с прогрессом и кнопкой «Отмена»).
 * Очередь последовательная. Все запросы идут через локальный прокси браузера (см. [MediaGrabber.open]).
 *
 * Режимы ([MediaGrabber.DownloadJob.mode]):
 *  • single        — один файл потоком в MediaStore;
 *  • merge         — видео + аудио (две ссылки) → склейка MediaMuxer без перекодирования;
 *  • hls           — плейлист m3u8 (сегменты, AES-128) → один файл .ts/.mp4/.aac;
 *  • audio_extract — скачать mp4/webm и вытащить аудиодорожку (m4a / webm);
 *  • hls_audio     — то же для HLS-потока.
 */
class MediaDownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "omni_downloads"
        const val ACTION_START = "com.winwasm64.action.MEDIA_DOWNLOAD"
        const val ACTION_CANCEL = "com.winwasm64.action.MEDIA_DOWNLOAD_CANCEL"
        const val ACTION_PAUSE = "com.winwasm64.action.MEDIA_DOWNLOAD_PAUSE"
        const val ACTION_RESUME = "com.winwasm64.action.MEDIA_DOWNLOAD_RESUME"
        const val ACTION_RESUME_ALL = "com.winwasm64.action.MEDIA_DOWNLOAD_RESUME_ALL"
        const val EXTRA_JOB = "job"
        const val EXTRA_ID = "id"
        private const val NOTIF_PROGRESS = 7401
        private const val CHUNK = 8L * 1024 * 1024
        private val doneCounter = java.util.concurrent.atomic.AtomicInteger(7500)
        /** v4.4.0: вкладки, страницы которых сейчас качают файл (закрытие прервёт загрузку). */
        val activeTabIds: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

        fun start(context: Context, job: MediaGrabber.DownloadJob) {
            val e = DownloadQueue.add(context, job)
            send(context, Intent(context, MediaDownloadService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, e.id), foreground = true)
        }

        /** v4.5.0: пауза/продолжение/удаление по id записи очереди (см. [DownloadQueue]). */
        fun pause(context: Context, id: String) = send(context, Intent(context, MediaDownloadService::class.java).setAction(ACTION_PAUSE).putExtra(EXTRA_ID, id), foreground = false)
        fun resume(context: Context, id: String) {
            DownloadQueue.setState(context, id, DownloadQueue.QUEUED)
            send(context, Intent(context, MediaDownloadService::class.java).setAction(ACTION_RESUME).putExtra(EXTRA_ID, id), foreground = true)
        }
        fun cancel(context: Context, id: String) = send(context, Intent(context, MediaDownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id), foreground = false)

        /** При старте приложения: если в очереди остались незавершённые загрузки — продолжаем их. */
        fun resumePending(context: Context) {
            DownloadQueue.load(context)
            DownloadQueue.sweep(context)
            if (!DownloadQueue.hasPending()) return
            send(context, Intent(context, MediaDownloadService::class.java).setAction(ACTION_RESUME_ALL), foreground = true)
        }

        private fun send(context: Context, i: Intent, foreground: Boolean) {
            try {
                if (foreground) ContextCompat.startForegroundService(context, i) else context.startService(i)
            } catch (e: Exception) {
                try { Toast.makeText(context, "Не удалось запустить загрузку: ${e.message}", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
            }
        }
    }

    private val queue = LinkedBlockingQueue<DownloadQueue.Entry>()
    private var worker: Thread? = null
    private val cancelCurrent = AtomicBoolean(false)
    private val pauseCurrent = AtomicBoolean(false)
    @Volatile private var current: DownloadQueue.Entry? = null
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Загрузки видео и аудио", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Прогресс загрузок из браузера"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DownloadQueue.load(this)
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_CANCEL -> {
                val cur = current
                if (id == null || cur?.id == id) {
                    cancelCurrent.set(true)
                    if (id == null) { queue.forEach { DownloadQueue.remove(this, it.id) }; queue.clear() }
                } else {
                    queue.removeAll { it.id == id }
                    DownloadQueue.remove(this, id)
                    try { nm.cancel(pausedNotifId(id)) } catch (_: Exception) {}
                }
                if (current == null) stopIfIdle()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                val cur = current
                if (id != null && cur?.id == id) pauseCurrent.set(true)
                else if (id != null) {
                    queue.removeAll { it.id == id }
                    DownloadQueue.setState(this, id, DownloadQueue.PAUSED)
                    notifyPaused(DownloadQueue.get(id))
                }
                if (current == null) stopIfIdle()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_RESUME -> {
                val e = id?.let { DownloadQueue.get(it) }
                if (e == null) { stopIfIdle(); return START_NOT_STICKY }
                try { nm.cancel(pausedNotifId(e.id)) } catch (_: Exception) {}
                if (current == null) goForeground(buildProgressNotification(e, -1, e.done, e.total, 0, "в очереди"))
                if (e.state != DownloadQueue.RUNNING && queue.none { it.id == e.id }) {
                    DownloadQueue.setState(this, e.id, DownloadQueue.QUEUED)
                    queue.add(e)
                }
                val job = e.job
                VpnLog.i(MediaGrabber.TAG, (if (intent.action == ACTION_RESUME) "продолжаем: " else "в очередь: ") + "«${job.fileName}» (${job.mode}${if (job.expectedBytes > 0) ", ~" + MediaGrabber.fmtSize(job.expectedBytes) else ""}${if (e.done > 0) ", есть " + MediaGrabber.fmtSize(DownloadQueue.partsBytes(this, e.id)) else ""})")
                ensureWorker()
            }
            ACTION_RESUME_ALL -> {
                val pend = DownloadQueue.queued().filter { q -> queue.none { it.id == q.id } && current?.id != q.id }
                if (pend.isEmpty() && current == null) { stopIfIdle(); return START_NOT_STICKY }
                pend.firstOrNull()?.let { if (current == null) goForeground(buildProgressNotification(it, -1, it.done, it.total, 0, "в очереди")) }
                queue.addAll(pend)
                if (pend.isNotEmpty()) VpnLog.i(MediaGrabber.TAG, "продолжаем после перезапуска: ${pend.size} загрузк(и)")
                ensureWorker()
            }
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_PROGRESS, n)
            }
        } catch (e: Exception) {
            VpnLog.w(MediaGrabber.TAG, "startForeground: ${e.message}")
        }
    }

    private fun stopIfIdle() {
        if (current == null && queue.isEmpty()) {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf()
        }
    }

    @Synchronized
    private fun ensureWorker() {
        if (worker?.isAlive == true) return
        worker = Thread({
            while (true) {
                val entry = queue.poll(2, TimeUnit.SECONDS) ?: break
                val job = entry.job
                current = entry
                cancelCurrent.set(false)
                pauseCurrent.set(false)
                DownloadQueue.setState(this, entry.id, DownloadQueue.RUNNING)
                if (job.inPage && job.tabId.isNotBlank()) activeTabIds.add(job.tabId)
                try {
                    runJob(entry)
                    DownloadQueue.remove(this, entry.id)
                } catch (e: PausedException) {
                    VpnLog.i(MediaGrabber.TAG, "пауза: «${job.fileName}» на ${MediaGrabber.fmtSize(DownloadQueue.partsBytes(this, entry.id)).ifBlank { "0" }}")
                    DownloadQueue.setState(this, entry.id, DownloadQueue.PAUSED)
                    notifyPaused(entry)
                } catch (e: CancelledException) {
                    VpnLog.w(MediaGrabber.TAG, "отменено: «${job.fileName}»")
                    DownloadQueue.remove(this, entry.id)
                    notifyDone(job, null, "Загрузка отменена")
                } catch (e: Throwable) {
                    val msg = e.message ?: e.javaClass.simpleName
                    VpnLog.e(MediaGrabber.TAG, "ошибка «${job.fileName}»: $msg")
                    // части остаются: «Повторить» в списке загрузок продолжит с того же места
                    DownloadQueue.setState(this, entry.id, DownloadQueue.ERROR, msg)
                    notifyDone(job, null, "Ошибка: $msg")
                } finally {
                    current = null
                    if (job.tabId.isNotBlank()) activeTabIds.remove(job.tabId)
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { stopIfIdle() }
        }, "omni-media-dl").apply { isDaemon = true; start() }
    }

    private class CancelledException : IOException("cancelled")
    private class PausedException : IOException("paused")

    private fun checkCancel() {
        if (cancelCurrent.get()) throw CancelledException()
        if (pauseCurrent.get()) throw PausedException()
    }
    private fun interrupted(): Boolean = cancelCurrent.get() || pauseCurrent.get()

    // ------------------------------------------------------------------ уведомления

    private fun piFlags() = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
    private fun cancelPending(): PendingIntent = PendingIntent.getService(this, 1, Intent(this, MediaDownloadService::class.java).setAction(ACTION_CANCEL), piFlags())
    private fun actionPending(action: String, id: String, req: Int): PendingIntent =
        PendingIntent.getService(this, req, Intent(this, MediaDownloadService::class.java).setAction(action).putExtra(EXTRA_ID, id), piFlags())
    private fun pausedNotifId(id: String): Int = 7600 + (id.hashCode() and 0xfff)

    private fun notifyPaused(e: DownloadQueue.Entry?) {
        if (e == null) return
        val text = buildString {
            append("На паузе")
            if (e.done > 0) append(" · ").append(MediaGrabber.fmtSize(e.done))
            if (e.total > 0) append(" из ").append(MediaGrabber.fmtSize(e.total))
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle(e.job.fileName)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_media_play, "Продолжить", actionPending(ACTION_RESUME, e.id, pausedNotifId(e.id) + 1))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Удалить", actionPending(ACTION_CANCEL, e.id, pausedNotifId(e.id) + 2))
            .build()
        try { nm.notify(pausedNotifId(e.id), n) } catch (_: Exception) {}
    }

    private fun buildProgressNotification(e: DownloadQueue.Entry, percent: Int, done: Long, total: Long, speedBps: Long, stage: String): Notification {
        val job = e.job
        val text = buildString {
            if (percent >= 0) append("$percent% · ")
            if (done > 0) append(MediaGrabber.fmtSize(done))
            if (total > 0) append(" из ").append(MediaGrabber.fmtSize(total))
            if (speedBps > 0) append(" · ").append(MediaGrabber.fmtSize(speedBps)).append("/с")
            if (stage.isNotBlank()) { if (isNotEmpty()) append(" · "); append(stage) }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(job.fileName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, if (percent < 0) 0 else percent, percent < 0)
            .addAction(android.R.drawable.ic_media_pause, "Пауза", actionPending(ACTION_PAUSE, e.id, 2))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отмена", cancelPending())
            .build()
    }

    private var lastNotifAt = 0L
    private fun progress(job: MediaGrabber.DownloadJob, done: Long, total: Long, speedBps: Long, stage: String, force: Boolean = false) {
        val e = current ?: return
        DownloadQueue.progress(e.id, done, total, speedBps, stage)
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifAt < 700) return
        lastNotifAt = now
        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
        try { nm.notify(NOTIF_PROGRESS, buildProgressNotification(e, pct, done, total, speedBps, stage)) } catch (_: Exception) {}
    }

    private fun notifyDone(job: MediaGrabber.DownloadJob, uri: Uri?, text: String) {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (uri != null) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(job.fileName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        if (uri != null) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, job.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            b.setContentIntent(PendingIntent.getActivity(this, doneCounter.get(), view, PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)))
        }
        try { nm.notify(doneCounter.incrementAndGet(), b.build()) } catch (_: Exception) {}
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { Toast.makeText(applicationContext, if (uri != null) "✅ $text" else "⚠ ${job.fileName}: $text", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ выполнение

    private fun runJob(entry: DownloadQueue.Entry) {
        val job = entry.job
        val t0 = System.currentTimeMillis()
        progress(job, DownloadQueue.partsBytes(this, entry.id), job.expectedBytes, 0, "подключение…", force = true)
        // v4.5.0: все режимы качают в части filesDir/dl/<id>.pN — пауза/обрыв/перезапуск продолжают с того же места
        val p0 = DownloadQueue.partFile(this, entry.id, 0)
        val p1 = DownloadQueue.partFile(this, entry.id, 1)
        val result: Uri = when (job.mode) {
            "single" -> {
                downloadUrl(job, 0, p0, job.expectedBytes, 0L, 1)
                progress(job, p0.length(), p0.length(), 0, "сохранение…", force = true)
                publishFile(job, p0)
            }
            "merge" -> {
                val vSize = downloadUrl(job, 0, p0, job.expectedBytes, 0L, 2)
                downloadUrl(job, 1, p1, job.expectedBytes, vSize, 2)
                progress(job, 0, 0, 0, "склейка видео и звука…", force = true)
                val merged = tmp("m", extOf(job.fileName))
                try {
                    Muxer.merge(p0, p1, merged, job.fileName.endsWith(".webm"))
                    publishFile(job, merged)
                } finally { merged.delete() }
            }
            "audio_extract" -> {
                downloadUrl(job, 0, p0, job.expectedBytes, 0L, 1)
                progress(job, 0, 0, 0, "извлечение звука…", force = true)
                val outF = tmp("x", extOf(job.fileName))
                try {
                    Muxer.extractAudio(p0, outF, job.fileName.endsWith(".webm"))
                    publishFile(job, outF)
                } finally { outF.delete() }
            }
            "hls" -> {
                downloadHls(entry, job.urls[0], p0)
                progress(job, p0.length(), p0.length(), 0, "сохранение…", force = true)
                publishFile(job, p0)
            }
            "hls_audio" -> {
                downloadHls(entry, job.urls[0], p0)
                progress(job, 0, 0, 0, "извлечение звука…", force = true)
                val outF = tmp("x", "m4a")
                try {
                    Muxer.extractAudio(p0, outF, false)
                    publishFile(job, outF)
                } finally { outF.delete() }
            }
            else -> throw IOException("неизвестный режим ${job.mode}")
        }
        DownloadQueue.deleteParts(this, entry.id)
        val sec = (System.currentTimeMillis() - t0) / 1000
        val mainPath = MediaSink.lastPathHint
        VpnLog.ok(MediaGrabber.TAG, "готово: «${job.fileName}» за ${sec} с → $mainPath")
        notifyDone(job, result, "Сохранено: $mainPath")
        try { downloadExtras(job) } catch (e: CancelledException) { throw e } catch (e: Exception) {
            VpnLog.w(MediaGrabber.TAG, "дополнения (субтитры/обложка): ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** v4.4.0: субтитры (.srt → Download/OmniBrowser) и обложка (.jpg → Pictures/OmniBrowser) после основного файла. */
    private fun downloadExtras(job: MediaGrabber.DownloadJob) {
        if (job.extras.isEmpty()) return
        val stem = MediaGrabber.safeName(job.fileName.substringBeforeLast('.'), "video")
        val ua = mapOf("User-Agent" to (job.headers["User-Agent"] ?: MediaGrabber.DEFAULT_UA))
        val subUrl = job.extras["sub_url"] ?: ""
        if (subUrl.isNotBlank()) {
            val url = subUrl + (if (subUrl.contains('?')) "&" else "?") + "fmt=json3"
            var text: String? = if (job.tabId.isNotBlank()) InPageDownloads.fetchText(job.tabId, url, 20_000L) else null
            if (text == null) text = try { String(fetchBytes(url, ua, null), Charsets.UTF_8) } catch (e: Exception) { VpnLog.w(MediaGrabber.TAG, "субтитры: ${e.message}"); null }
            if (text != null) {
                val srt = json3ToSrt(text)
                if (srt.isNotBlank()) {
                    val lang = job.extras["sub_lang"] ?: "sub"
                    val where = DownloadSaver.saveBytes(this, "$stem.$lang.srt", "application/x-subrip", srt.toByteArray(Charsets.UTF_8))
                    VpnLog.i(MediaGrabber.TAG, "субтитры ($lang) → $where")
                } else VpnLog.w(MediaGrabber.TAG, "субтитры: пустой ответ (${text.length} зн.)")
            }
        }
        val thumb = job.extras["thumb_url"] ?: ""
        if (thumb.isNotBlank()) {
            try {
                val bytes = fetchBytes(thumb, ua, null)
                if (bytes.size > 1000) {
                    val where = saveImage("$stem.jpg", bytes)
                    VpnLog.i(MediaGrabber.TAG, "обложка → $where")
                }
            } catch (e: Exception) { VpnLog.w(MediaGrabber.TAG, "обложка: ${e.message}") }
        }
    }

    private fun saveImage(name: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rel = Environment.DIRECTORY_PICTURES + "/OmniBrowser"
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("медиатека недоступна")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("не удалось открыть файл")
            cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, cv, null, null)
            return "$rel/$name"
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "OmniBrowser").apply { mkdirs() }
        val f = File(dir, name)
        f.writeBytes(bytes)
        MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null)
        return f.absolutePath
    }

    /** YouTube timedtext fmt=json3 → SubRip. */
    private fun json3ToSrt(json: String): String {
        val o = try { org.json.JSONObject(json) } catch (_: Exception) { return "" }
        val events = o.optJSONArray("events") ?: return ""
        val sb = StringBuilder()
        var n = 0
        fun ts(ms: Long): String = String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val segs = ev.optJSONArray("segs") ?: continue
            val text = StringBuilder()
            for (j in 0 until segs.length()) text.append(segs.optJSONObject(j)?.optString("utf8") ?: "")
            val t = text.toString().trim()
            if (t.isEmpty() || t == "\n") continue
            val start = ev.optLong("tStartMs")
            var dur = ev.optLong("dDurationMs")
            if (dur <= 0) {
                val next = events.optJSONObject(i + 1)
                dur = if (next != null) (next.optLong("tStartMs") - start).coerceAtLeast(500) else 3000
            }
            n++
            sb.append(n).append('\n').append(ts(start)).append(" --> ").append(ts(start + dur)).append('\n').append(t).append("\n\n")
        }
        return sb.toString()
    }

    private fun extOf(name: String): String = name.substringAfterLast('.', "bin")

    private fun tmp(prefix: String, ext: String): File {
        val dir = File(cacheDir, "media_dl").apply { mkdirs() }
        return File(dir, "$prefix-${System.nanoTime()}.$ext")
    }

    private fun publishFile(job: MediaGrabber.DownloadJob, f: File): Uri {
        val sink = MediaSink.create(this, job)
        try {
            f.inputStream().use { ins -> sink.stream.use { out -> ins.copyTo(out, 256 * 1024) } }
            return sink.finish()
        } catch (e: Throwable) { sink.abort(); throw e }
    }

    // ------------------------------------------------------------------ источники

    private class Resp(val code: Int, val contentRange: String?, val contentLength: Long, val body: InputStream, val closer: () -> Unit)

    private interface Fetcher {
        fun get(url: String, headers: Map<String, String>, range: String?): Resp
    }

    /** Обычный путь: HttpURLConnection через локальный прокси браузера (маршрут по PAC/гонке, как у страницы). */
    private object ProxyFetcher : Fetcher {
        override fun get(url: String, headers: Map<String, String>, range: String?): Resp {
            val c = MediaGrabber.open(url, headers)
            if (range != null) c.setRequestProperty("Range", range)
            val code = c.responseCode
            val body: InputStream = try { if (code in 200..299) c.inputStream else (c.errorStream ?: ByteArrayInputStreamEmpty()) } catch (_: Exception) { ByteArrayInputStreamEmpty() }
            return Resp(code, c.getHeaderField("Content-Range"), c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L, body) { try { c.disconnect() } catch (_: Exception) {} }
        }
        private fun ByteArrayInputStreamEmpty(): InputStream = java.io.ByteArrayInputStream(ByteArray(0))
    }

    /** Закреплённый сервер Browsec (YouTube): все соединения через один IP. */
    private class PinnedFetcher(val http: TunnelHttp) : Fetcher {
        override fun get(url: String, headers: Map<String, String>, range: String?): Resp {
            val r = http.request(url, headers, "GET", null, if (range != null) mapOf("Range" to range) else emptyMap())
            return Resp(r.code, r.header("content-range"), r.header("content-length")?.toLongOrNull() ?: -1L, r.body) { r.close() }
        }
    }

    private fun fetcherFor(job: MediaGrabber.DownloadJob): Fetcher =
        if (job.server.isNotBlank()) PinnedFetcher(TunnelHttp(job.server)) else ProxyFetcher

    /** YouTube ответил 403 или закреплённый сервер упал: новые адреса для тех же itag через другой сервер. */
    private fun refreshYouTube(job: MediaGrabber.DownloadJob, why: String): Fetcher? {
        if (job.ytId.isBlank() || job.itags.size != job.urls.size || job.itags.isEmpty()) return null
        VpnLog.w(MediaGrabber.TAG, "YouTube: $why — запрашиваю свежие адреса через другой сервер")
        val r = MediaGrabber.ytRefresh(job.ytId, job.itags) ?: return null
        for (i in r.first.indices) job.urls[i] = r.first[i]
        job.server = r.second
        VpnLog.i(MediaGrabber.TAG, "YouTube: адреса обновлены, сервер ${r.second}")
        return PinnedFetcher(TunnelHttp(r.second))
    }

    /**
     * Качает ссылку №[index] задания в поток кусками по 8 МБ (Range) с докачкой при обрыве. Возвращает число байт.
     * @param parts число частей задания (для процента), [already] — байты, скачанные предыдущими частями
     */
    private fun downloadUrl(job: MediaGrabber.DownloadJob, index: Int, part: File, expectedTotal: Long, already: Long, parts: Int): Long {
        var out: OutputStream = FileOutputStream(part, true)
        try {
            return downloadUrlTo(job, index, part, { out }, { out.close(); part.delete(); out = FileOutputStream(part, false) }, expectedTotal, already, parts)
        } finally { try { out.close() } catch (_: Exception) {} }
    }

    private fun downloadUrlTo(job: MediaGrabber.DownloadJob, index: Int, part: File, outRef: () -> OutputStream, restart: () -> Unit, expectedTotal: Long, already: Long, parts: Int): Long {
        var fetcher = fetcherFor(job)
        var pos = part.length()
        if (pos > 0) VpnLog.i(MediaGrabber.TAG, "часть ${index + 1}/$parts: продолжаем с ${MediaGrabber.fmtSize(pos)}")
        var out = outRef()
        var total = -1L
        var attempts = 0
        var refreshes = 0
        var ipPinTried = job.server.isNotBlank()
        var rangesOk = true
        val buf = ByteArray(128 * 1024)
        var lastTick = System.currentTimeMillis()
        var tickBytes = 0L
        var speed = 0L
        // v4.3.6: адреса плеера страницы сначала качает сама страница (её fetch сервер принимает, наш — 403)
        if (job.inPage && InPageDownloads.available(job.tabId)) {
            checkCancel()
            val url = job.urls[index]
            val clen = try { Uri.parse(url).getQueryParameter("clen")?.toLongOrNull() ?: 0L } catch (_: Exception) { 0L }
            val shown = if (expectedTotal > 0) expectedTotal else if (clen > 0) clen * parts else 0L
            if (job.server.isNotBlank()) MediaGrabber.pinHosts(job.urls, job.server)
            var tries = 0
            while (tries < 3) {
                tries++
                checkCancel()
                var lastP = System.currentTimeMillis()
                var lastB = 0L
                val base = pos
                val t = InPageDownloads.start(job.tabId, url, clen, out, base) { b ->
                    val now = System.currentTimeMillis()
                    if (now - lastP >= 1000) {
                        speed = (b - lastB) * 1000 / (now - lastP); lastP = now; lastB = b
                        progress(job, already + base + b, shown, speed, if (parts > 1) "часть ${index + 1} из $parts" else "")
                    }
                }
                if (t == null) { VpnLog.w(MediaGrabber.TAG, "вкладка закрыта — качаем из приложения"); break }
                if (tries == 1) VpnLog.i(MediaGrabber.TAG, "часть ${index + 1}/$parts: качает сама страница (${MediaGrabber.fmtSize(clen).ifBlank { "размер неизвестен" }})")
                val ok = InPageDownloads.await(t, 60_000L) { interrupted() }
                checkCancel()
                pos = base + t.bytes
                if (ok) { out.flush(); return pos }
                val err = t.error ?: "?"
                if (err.startsWith("HTTP") || tries >= 3) {
                    VpnLog.w(MediaGrabber.TAG, "страница не смогла скачать ($err) на ${MediaGrabber.fmtSize(pos).ifBlank { "0" }} — пробуем из приложения")
                    break
                }
                VpnLog.w(MediaGrabber.TAG, "обрыв загрузки страницей ($err) на ${MediaGrabber.fmtSize(pos).ifBlank { "0" }} — повтор $tries/3")
                Thread.sleep(2000L)
            }
        }
        while (true) {
            checkCancel()
            val url = job.urls[index]
            val isGoogle = url.contains("googlevideo.com")
            val end = if (rangesOk) pos + CHUNK - 1 else -1L
            var resp: Resp? = null
            try {
                resp = fetcher.get(url, job.headers, if (rangesOk) (if (end >= 0) "bytes=$pos-$end" else "bytes=$pos-") else null)
                val code = resp.code
                when {
                    code == 206 -> {
                        if (total < 0) total = (resp.contentRange ?: "").substringAfterLast('/', "").trim().toLongOrNull() ?: -1L
                    }
                    code == 200 -> {
                        if (pos > 0) {
                            VpnLog.w(MediaGrabber.TAG, "сервер не поддерживает докачку — качаем часть ${index + 1} заново")
                            restart(); out = outRef(); pos = 0L
                        }
                        rangesOk = false
                        if (total < 0) total = resp.contentLength
                    }
                    code == 416 -> break
                    (code == 403 || code == 410) && isGoogle -> {
                        val webMode = job.headers.containsKey("Referer")
                        if (webMode) {
                            // адреса плеера страницы: единственное лекарство от 403 — тот же IP, что в ссылке
                            if (!ipPinTried) {
                                ipPinTried = true
                                resp.closer(); resp = null
                                val ip = try { android.net.Uri.parse(url).getQueryParameter("ip") ?: "" } catch (_: Exception) { "" }
                                val srv = MediaGrabber.findServerForIp(ip)
                                if (srv != null && srv != job.server) { job.server = srv; fetcher = PinnedFetcher(TunnelHttp(srv)); attempts = 0; continue }
                            }
                            throw IOException("YouTube отклонил ссылку (HTTP $code): адрес привязан к другому IP. Перезагрузите страницу (чтобы она открылась через доступный сервер), запустите видео и повторите")
                        }
                        if (refreshes < 2) {
                            refreshes++
                            resp.closer(); resp = null
                            val nf = refreshYouTube(job, "HTTP $code на ${MediaGrabber.fmtSize(pos).ifBlank { "0" }}")
                            if (nf != null) { fetcher = nf; attempts = 0; continue }
                        }
                        throw IOException("YouTube отклонил ссылку (HTTP $code) — ни адреса плеера, ни InnerTube не подошли. Запустите видео на странице на пару секунд и повторите")
                    }
                    else -> throw IOException("HTTP $code")
                }
                val shown = if (expectedTotal > 0) expectedTotal else if (total > 0) total * parts else 0L
                val ins: InputStream = resp.body
                var got = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    pos += n; got += n; tickBytes += n
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= 1000) {
                        speed = tickBytes * 1000 / (now - lastTick)
                        tickBytes = 0; lastTick = now
                        progress(job, already + pos, shown, speed, if (parts > 1) "часть ${index + 1} из $parts" else "")
                    }
                    if (interrupted()) { out.flush(); checkCancel() }
                }
                attempts = 0
                if (!rangesOk) break
                if (total in 1..pos) break
                if (got == 0L) break
                if (total < 0 && got < CHUNK) break
            } catch (e: CancelledException) {
                throw e
            } catch (e: PausedException) {
                throw e
            } catch (e: IOException) {
                attempts++
                if (attempts > 5 || !rangesOk || e.message?.startsWith("YouTube отклонил") == true) throw e
                VpnLog.w(MediaGrabber.TAG, "обрыв на ${MediaGrabber.fmtSize(pos).ifBlank { "0" }} (${e.message}) — повтор $attempts/5")
                // закреплённый сервер мог упасть — для YouTube переезжаем на другой вместе с новыми адресами
                if (job.server.isNotBlank() && attempts >= 2 && refreshes < 3) {
                    refreshes++
                    refreshYouTube(job, e.message ?: "обрыв")?.let { fetcher = it }
                }
                Thread.sleep(1500L * attempts)
            } finally {
                try { resp?.closer?.invoke() } catch (_: Exception) {}
            }
        }
        out.flush()
        return pos
    }


    // ------------------------------------------------------------------ HLS

    private class Segment(val url: String, val keyUrl: String?, val iv: ByteArray?)

    private fun downloadHls(entry: DownloadQueue.Entry, playlistUrl: String, part: File) {
        val job = entry.job
        val meta = DownloadQueue.metaFile(this, entry.id, "seg")
        // сколько сегментов уже записано в part (пауза/обрыв) — продолжаем со следующего
        var skip = if (part.length() > 0) (try { meta.readText().trim().toInt() } catch (_: Exception) { 0 }) else 0
        if (skip > 0) VpnLog.i(MediaGrabber.TAG, "HLS: продолжаем с сегмента ${skip + 1} (${MediaGrabber.fmtSize(part.length())})")
        else { part.delete(); meta.delete() }
        FileOutputStream(part, true).use { out -> downloadHlsTo(job, playlistUrl, out, skip, part) { n -> try { meta.writeText(n.toString()) } catch (_: Exception) {} } }
    }

    private fun downloadHlsTo(job: MediaGrabber.DownloadJob, playlistUrl: String, out: OutputStream, skip: Int, part: File, onSeg: (Int) -> Unit) {
        var url = playlistUrl
        val http: TunnelHttp? = if (job.server.isNotBlank()) TunnelHttp(job.server) else null
        fun text(u: String): String = if (http != null) http.getText(u, job.headers, 4 * 1024 * 1024) else MediaGrabber.getText(u, job.headers, 4 * 1024 * 1024)
        var text = text(url)
        if (text.contains("#EXT-X-STREAM-INF")) {
            val v = MediaGrabber.parseMaster(text, url).filter { !it.audioOnly }.maxByOrNull { it.bandwidth }
                ?: MediaGrabber.parseMaster(text, url).firstOrNull() ?: throw IOException("пустой мастер-плейлист")
            url = v.url
            text = text(url)
        }
        val segs = ArrayList<Segment>()
        var initUrl: String? = null
        var keyUrl: String? = null
        var keyIv: ByteArray? = null
        var seq = 0L
        var method = "NONE"
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) seq = l.substringAfter(':').trim().toLongOrNull() ?: 0L
            else if (l.startsWith("#EXT-X-KEY:")) {
                val a = attrs(l.substringAfter(':'))
                method = a["METHOD"] ?: "NONE"
                if (method == "AES-128") {
                    keyUrl = a["URI"]?.let { MediaGrabber.resolveUrl(url, it) }
                    keyIv = a["IV"]?.let { hex(it) }
                } else if (method == "NONE") { keyUrl = null; keyIv = null }
                else throw IOException("шифрование $method не поддерживается")
            } else if (l.startsWith("#EXT-X-MAP:")) {
                val a = attrs(l.substringAfter(':'))
                initUrl = a["URI"]?.let { MediaGrabber.resolveUrl(url, it) }
            } else if (l.startsWith("#")) continue
            else {
                val iv = if (keyUrl != null) (keyIv ?: seqIv(seq)) else null
                segs.add(Segment(MediaGrabber.resolveUrl(url, l), keyUrl, iv))
                seq++
            }
        }
        if (segs.isEmpty()) throw IOException("в плейлисте нет сегментов")
        VpnLog.i(MediaGrabber.TAG, "HLS: ${segs.size} сегментов${if (initUrl != null) " + init" else ""}${if (segs.any { it.keyUrl != null }) ", AES-128" else ""}")
        val keys = HashMap<String, ByteArray>()
        var done = part.length()
        var lastTick = System.currentTimeMillis()
        var tickBytes = 0L
        var speed = 0L
        val expectedPerSeg = if (job.expectedBytes > 0) job.expectedBytes / segs.size else 0L
        if (initUrl != null && skip == 0) {
            val b = fetchBytes(initUrl, job.headers, http)
            out.write(b); done += b.size
        }
        for ((i, s) in segs.withIndex()) {
            if (i < skip) continue
            checkCancel()
            var data = fetchBytes(s.url, job.headers, http)
            if (s.keyUrl != null) {
                val key = keys.getOrPut(s.keyUrl) { fetchBytes(s.keyUrl, job.headers, http) }
                if (key.size != 16) throw IOException("ключ AES неверной длины")
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(s.iv))
                data = cipher.doFinal(data)
            }
            out.write(data)
            out.flush()
            onSeg(i + 1)
            done += data.size; tickBytes += data.size
            val now = System.currentTimeMillis()
            if (now - lastTick >= 1000) { speed = tickBytes * 1000 / (now - lastTick); tickBytes = 0; lastTick = now }
            val estTotal = if (expectedPerSeg > 0) job.expectedBytes else if (i > 0) done * segs.size / (i + 1) else 0L
            progress(job, done, estTotal, speed, "сегмент ${i + 1}/${segs.size}")
        }
        out.flush()
    }

    private fun fetchBytes(url: String, headers: Map<String, String>, http: TunnelHttp?): ByteArray {
        var last: IOException? = null
        for (attempt in 1..4) {
            checkCancel()
            try {
                if (http != null) {
                    http.request(url, headers).use { r ->
                        if (r.code !in 200..299) throw IOException("HTTP ${r.code}")
                        return MediaGrabber.readAll(r.body, 256 * 1024 * 1024)
                    }
                } else {
                    val c = MediaGrabber.open(url, headers)
                    try {
                        val code = c.responseCode
                        if (code !in 200..299) throw IOException("HTTP $code")
                        return MediaGrabber.readAll(c.inputStream, 256 * 1024 * 1024)
                    } finally { c.disconnect() }
                }
            } catch (e: CancelledException) { throw e } catch (e: IOException) {
                last = e
                Thread.sleep(1000L * attempt)
            }
        }
        throw last ?: IOException("segment")
    }

    private fun attrs(line: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        val re = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")
        for (r in re.findAll(line)) m[r.groupValues[1]] = r.groupValues[2].trim('"')
        return m
    }

    private fun hex(s: String): ByteArray {
        val h = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(16) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun seqIv(seq: Long): ByteArray = ByteArray(16).also { ByteBuffer.wrap(it).putLong(8, seq) }

    // ------------------------------------------------------------------ сохранение в медиатеку

    /** Куда писать результат: MediaStore (Android 10+) или общая папка Movies/Music. */
    private class MediaSink(private val ctx: Context, val stream: OutputStream, private val uri: Uri?, private val file: File?, private val mime: String) {
        companion object {
            @Volatile var lastPathHint: String = ""

            fun create(ctx: Context, job: MediaGrabber.DownloadJob): MediaSink {
                val isAudio = job.isAudio || job.mime.startsWith("audio/")
                val sub = "OmniBrowser"
                val name = MediaGrabber.safeName(job.fileName.substringBeforeLast('.'), "video") + "." + job.fileName.substringAfterLast('.', "bin")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val collection = if (isAudio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    val rel = (if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/" + sub
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, job.mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                        if (job.title.isNotBlank()) put(MediaStore.MediaColumns.TITLE, job.title.take(200))
                    }
                    val uri = try { ctx.contentResolver.insert(collection, cv) } catch (_: Exception) { null }
                    if (uri != null) {
                        val os = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("не удалось открыть файл в медиатеке")
                        lastPathHint = "$rel/$name"
                        return MediaSink(ctx, os, uri, null, job.mime)
                    }
                    // запасной вариант — папка Downloads
                    val cv2 = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, job.mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + sub)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri2 = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv2) ?: throw IOException("медиатека недоступна")
                    val os2 = ctx.contentResolver.openOutputStream(uri2) ?: throw IOException("не удалось открыть файл")
                    lastPathHint = "Download/$sub/$name"
                    return MediaSink(ctx, os2, uri2, null, job.mime)
                }
                @Suppress("DEPRECATION")
                val base = Environment.getExternalStoragePublicDirectory(if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES)
                var dir = File(base, sub)
                if (!dir.exists() && !dir.mkdirs() || !dir.canWrite()) {
                    dir = File(ctx.getExternalFilesDir(null), sub).apply { mkdirs() }
                }
                var f = File(dir, name)
                var n = 1
                val stem = name.substringBeforeLast('.')
                val ext = name.substringAfterLast('.', "")
                while (f.exists()) { f = File(dir, "$stem ($n).$ext"); n++ }
                lastPathHint = f.absolutePath
                return MediaSink(ctx, FileOutputStream(f), null, f, job.mime)
            }
        }

        fun finish(): Uri {
            try { stream.close() } catch (_: Exception) {}
            if (uri != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    try { ctx.contentResolver.update(uri, cv, null, null) } catch (_: Exception) {}
                }
                return uri
            }
            val f = file!!
            try { MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(mime), null) } catch (_: Exception) {}
            return try {
                androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            } catch (_: Exception) { Uri.fromFile(f) }
        }

        fun abort() {
            try { stream.close() } catch (_: Exception) {}
            try { if (uri != null) ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            try { file?.delete() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ склейка без перекодирования

    private object Muxer {
        private fun firstTrack(ex: MediaExtractor, prefix: String): Int {
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith(prefix)) return i
            }
            return -1
        }

        private fun copyTrack(ex: MediaExtractor, mux: MediaMuxer, dst: Int, buf: ByteBuffer) {
            val info = MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.offset = 0
                info.size = n
                info.presentationTimeUs = ex.sampleTime
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                mux.writeSampleData(dst, buf, info)
                if (!ex.advance()) break
            }
        }

        fun merge(video: File, audio: File, out: File, webm: Boolean) {
            val exV = MediaExtractor().apply { setDataSource(video.absolutePath) }
            val exA = MediaExtractor().apply { setDataSource(audio.absolutePath) }
            val tv = firstTrack(exV, "video/")
            val ta = firstTrack(exA, "audio/")
            if (tv < 0) throw IOException("в видеофайле нет видеодорожки")
            if (ta < 0) throw IOException("в аудиофайле нет аудиодорожки")
            val fmt = if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            val mux = MediaMuxer(out.absolutePath, fmt)
            try {
                exV.selectTrack(tv); exA.selectTrack(ta)
                val dv = mux.addTrack(exV.getTrackFormat(tv))
                val da = mux.addTrack(exA.getTrackFormat(ta))
                mux.start()
                val buf = ByteBuffer.allocateDirect(4 * 1024 * 1024)
                copyTrack(exV, mux, dv, buf)
                copyTrack(exA, mux, da, buf)
                mux.stop()
            } finally {
                try { mux.release() } catch (_: Exception) {}
                exV.release(); exA.release()
            }
        }

        fun extractAudio(src: File, out: File, webm: Boolean) {
            val ex = MediaExtractor().apply { setDataSource(src.absolutePath) }
            val ta = firstTrack(ex, "audio/")
            if (ta < 0) throw IOException("аудиодорожка не найдена")
            val mimeA = ex.getTrackFormat(ta).getString(MediaFormat.KEY_MIME) ?: ""
            val fmt = if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            if (!webm && !(mimeA.contains("mp4a") || mimeA.contains("aac") || mimeA.contains("mpeg"))) {
                throw IOException("кодек $mimeA нельзя положить в m4a без перекодирования")
            }
            val mux = MediaMuxer(out.absolutePath, fmt)
            try {
                ex.selectTrack(ta)
                val da = mux.addTrack(ex.getTrackFormat(ta))
                mux.start()
                copyTrack(ex, mux, da, ByteBuffer.allocateDirect(2 * 1024 * 1024))
                mux.stop()
            } finally {
                try { mux.release() } catch (_: Exception) {}
                ex.release()
            }
        }
    }
}
