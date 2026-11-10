package com.winwasm64.torrent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * v4.55.0: свой загрузчик вместо DownloadManager для «пойманных» потоков.
 *
 * Почему. В 4.54.0 скачивание ролика с YouTube формально «получалось», но файл весил 31 байт и не
 * открывался: DownloadManager сохранял ответ об ошибке (403/HTML) как видео и рапортовал «готово».
 * Здесь мы сами проверяем код ответа, тип содержимого и размер, качаем с нужными заголовками
 * (Range, User-Agent, Referer, Cookie), пишем в папку приложения и честно сообщаем об ошибке.
 *
 * Прогресс каждой загрузки виден во вкладке «Загрузки» вместе с системными.
 */
object StreamDownloader {

    const val STATE_RUNNING = "running"
    const val STATE_DONE = "done"
    const val STATE_ERROR = "error"

    data class Item(
        val id: Long,
        val name: String,
        val path: String,
        val bytes: Long,
        val total: Long,
        val state: String,
        val note: String
    ) {
        val progress: Int get() = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    private val jobs = ConcurrentHashMap<Long, Item>()
    private var counter = 1000L

    fun snapshot(): List<Item> = jobs.values.sortedByDescending { it.id }

    fun start(
        context: Context,
        url: String,
        fileName: String,
        userAgent: String? = null,
        cookie: String? = null,
        referer: String? = null,
        minBytes: Long = 100 * 1024,
        onFinish: (Boolean, String) -> Unit = { _, _ -> }
    ): Long {
        val id = synchronized(this) { counter++ }
        val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "video.mp4" }
        val dir = AppDownloads.folder().apply { mkdirs() }
        val target = File(dir, safeName)
        jobs[id] = Item(id, safeName, target.absolutePath, 0, 0, STATE_RUNNING, "соединяюсь…")
        persist(context, id, fileName = safeName, path = target.absolutePath, state = STATE_RUNNING, note = "качается")

        Thread({
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", userAgent ?: DESKTOP_UA)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                    // без Range некоторые CDN отдают 403
                    setRequestProperty("Range", "bytes=0-")
                    if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                    if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    fail(context, id, "сервер ответил $code")
                    onFinish(false, "сервер ответил $code")
                    return@Thread
                }
                val type = conn.contentType.orEmpty()
                if (type.contains("text/html", true) || type.contains("json", true)) {
                    fail(context, id, "вместо видео пришёл $type")
                    onFinish(false, "вместо видео пришёл $type")
                    return@Thread
                }
                val total = conn.contentLengthLong
                val raf = RandomAccessFile(target, "rw")
                var written = 0L
                try {
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var lastTick = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            raf.write(buffer, 0, read)
                            written += read
                            if (written - lastTick > 512 * 1024) {
                                lastTick = written
                                jobs[id] = jobs[id]!!.copy(bytes = written, total = total, note = "качается")
                            }
                        }
                    }
                } finally {
                    raf.close()
                }
                if (written < minBytes) {
                    target.delete()
                    fail(context, id, "файл всего ${written} Б — это не видео")
                    onFinish(false, "файл всего ${written} Б — это не видео")
                    return@Thread
                }
                jobs[id] = jobs[id]!!.copy(
                    bytes = written,
                    total = if (total > 0) total else written,
                    state = STATE_DONE,
                    note = "готово"
                )
                persist(context, id, safeName, target.absolutePath, STATE_DONE, "готово")
                onFinish(true, target.absolutePath)
            } catch (e: Throwable) {
                fail(context, id, e.message ?: "ошибка сети")
                onFinish(false, e.message ?: "ошибка сети")
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Throwable) {
                }
            }
        }, "StreamDl").also { it.isDaemon = true; it.start() }
        return id
    }

    private fun fail(context: Context, id: Long, note: String) {
        val item = jobs[id] ?: return
        jobs[id] = item.copy(state = STATE_ERROR, note = note)
        persist(context, id, item.name, item.path, STATE_ERROR, note)
    }

    fun remove(id: Long) {
        jobs.remove(id)
    }

    /* ===================== сохранение истории ===================== */

    private const val PREFS = "omni_stream_downloads"
    private const val KEY = "items"

    private fun persist(context: Context, id: Long, fileName: String, path: String, state: String, note: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = try {
                JSONArray(prefs.getString(KEY, "[]"))
            } catch (_: Throwable) {
                JSONArray()
            }
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optLong("id") != id && out.length() < 40) out.put(o)
            }
            out.put(
                JSONObject().apply {
                    put("id", id)
                    put("name", fileName)
                    put("path", path)
                    put("state", state)
                    put("note", note)
                    put("ts", System.currentTimeMillis())
                }
            )
            prefs.edit().putString(KEY, out.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    /** История прошлых запусков (после перезапуска приложения). */
    fun history(context: Context): List<Item> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString(KEY, "[]"))
        } catch (_: Throwable) {
            return emptyList()
        }
        val out = mutableListOf<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("path")
            val file = File(path)
            var state = o.optString("state")
            var note = o.optString("note")
            val size = if (file.exists()) file.length() else 0L
            // v4.57.0: в истории оставались «готово · 31 Б» — старые записи не перепроверялись.
            // Теперь статус сверяется с файлом: пусто/мало/нет файла — это не «готово».
            if (state == STATE_DONE && size < 50 * 1024) {
                state = STATE_ERROR
                note = if (!file.exists()) "файла нет — скачайте заново"
                else "файл всего $size Б — это не видео"
            }
            out.add(
                Item(
                    id = o.optLong("id"),
                    name = o.optString("name"),
                    path = path,
                    bytes = size,
                    total = size,
                    state = state,
                    note = note
                )
            )
        }
        return out
    }

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
}
