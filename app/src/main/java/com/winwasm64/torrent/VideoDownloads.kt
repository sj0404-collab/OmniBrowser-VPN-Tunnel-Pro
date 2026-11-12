package com.winwasm64.torrent

import android.content.Context
import android.os.Environment
import com.winwasm64.ui.browser.DownloadQueue
import com.winwasm64.ui.browser.MediaDownloadService
import com.winwasm64.ui.browser.MediaGrabber
import com.winwasm64.vpn.VpnLog
import java.io.File

/**
 * v4.81.0: скачивание видео с выбором качества — прямо из карточки.
 *
 * Раньше «скачать видео» жило только внутри старого экрана поиска. Теперь очередь общая с браузером
 * ([DownloadQueue] + [MediaDownloadService]): ролики качаются в Download/OmniBrowser, видны в «Загрузках»
 * с прогрессом, пауза/продолжение работают, а по завершении запись попадает в [VideoLibrary] как
 * «скачано» — и открывается офлайн встроенным плеером.
 */
object VideoDownloads {

    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private fun safeName(title: String, ext: String): String {
        val base = title.replace(Regex("""[\\/:*?"<>|]+"""), " ").trim().replace(Regex("\\s+"), " ")
        val t = base.ifBlank { "video" }.take(70)
        return "$t.$ext"
    }

    /**
     * Поставить в очередь выбранный поток. Возвращает текст для человека.
     */
    fun enqueue(ctx: Context, clip: VideoFeed.Clip, variant: VideoPlay.Variant): String {
        val headers = buildMap {
            put("User-Agent", UA)
            (variant.referer ?: "").takeIf { it.isNotBlank() }?.let {
                put("Referer", it)
                put("Origin", it.trimEnd('/'))
            }
        }
        val ext = if (variant.isHls) "ts" else "mp4"
        val name = safeName(clip.title + " [" + variant.label.substringBefore(" ·") + "]", ext)
        val job = MediaGrabber.DownloadJob(
            mode = if (variant.isHls) "hls" else "single",
            title = clip.title,
            fileName = name,
            mime = if (variant.isHls) "application/x-mpegURL" else "video/mp4",
            urls = mutableListOf(variant.url),
            headers = headers,
            expectedBytes = variant.bytes,
            ytId = VideoPlay.youtubeId(clip.url).orEmpty(),
            extras = if (clip.thumb.isNotBlank()) mapOf("thumb_url" to clip.thumb) else emptyMap()
        )
        val entry = DownloadQueue.add(ctx, job)
        MediaDownloadService.start(ctx, job)
        // запомним намерение: файл появится в Download/OmniBrowser/<name>
        val path = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "${AppDownloads.SUBDIR}/$name"
        ).absolutePath
        VideoLibrary.markDownloaded(ctx, clip, path)
        VpnLog.i("Видео", "в очередь: «${clip.title}» ${variant.label} → $name (${entry.id})")
        return "Скачиваю «${clip.title.take(40)}» · ${variant.label}"
    }

    /** Уже скачанный файл, если он есть на диске. */
    fun localFile(ctx: Context, clip: VideoFeed.Clip): File? {
        val saved = VideoLibrary.find(ctx, VideoFeed.keyOf(clip)) ?: return null
        if (saved.localPath.isBlank()) return null
        val f = File(saved.localPath)
        return if (f.isFile) f else null
    }
}
