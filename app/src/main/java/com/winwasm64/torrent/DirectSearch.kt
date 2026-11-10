package com.winwasm64.torrent

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v4.47.0: поиск БЕЗ торрентов — то, что можно скачать напрямую файлом.
 *
 * Зачем: у торрентов долгий путь (пиры, метаданные), а человеку часто нужно просто найти
 * «черепашки ниндзя — игра на свитч в XCI» или «программа для Windows в exe». Здесь ищем по
 * открытым архивам, где файл отдаётся обычной ссылкой:
 *
 *  • Internet Archive — крупнейший легальный архив: игры, книги, комиксы, манга, музыка, видео.
 *    У каждой записи есть обложка (`/services/img/<id>`) и файлы с настоящим форматом
 *    (EPUB, CBR, ISO, MP3, FLAC, ZIP…), ссылка на файл отдаётся напрямую по HTTPS.
 *  • GitHub Releases — релизы программ: .exe, .apk, .zip, .dmg, .AppImage с настоящим размером.
 *
 * Формат показываем как есть (XCI / NSP / EXE / RAR / ZIP / APK / EPUB / CBR / FLAC…) —
 * это и есть ответ на «на которых он есть и их формат».
 */
data class DirectFile(
    val title: String,
    val source: String,
    val pageUrl: String,
    val downloadUrl: String,
    val fileName: String,
    val format: String,
    val sizeLabel: String,
    val poster: String,
    val kind: String,
    val description: String
)

object DirectSearch {

    /** Расширения, которые считаем «полезным файлом», а не служебной мелочью архива. */
    private val USEFUL_EXT = setOf(
        "xci", "nsp", "nsz", "iso", "img", "nro", "wbfs", "wad", "rvz", "gcm",
        "exe", "msi", "apk", "apks", "xapk", "dmg", "pkg", "deb", "rpm", "appimage", "ipa",
        "rar", "zip", "7z", "tar", "gz", "torrent", "bin", "cue",
        "epub", "fb2", "mobi", "azw3", "djvu", "pdf", "cbr", "cbz", "cb7",
        "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav",
        "mp4", "mkv", "avi", "webm", "mov"
    )

    /** Служебные файлы Archive.org, которые не нужны человеку. */
    private val JUNK_EXT = setOf(
        "xml", "sqlite", "txt", "json", "log", "md5", "sha1", "torrent", "js", "css", "html"
    )

    fun search(query: String, timeoutMs: Int = 12_000): List<DirectFile> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val out = mutableListOf<DirectFile>()
        out.addAll(searchArchive(clean, timeoutMs))
        out.addAll(searchGitHub(clean, timeoutMs))
        return out
    }

    /* ===================== Internet Archive ===================== */

    private fun searchArchive(query: String, timeoutMs: Int): List<DirectFile> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://archive.org/advancedsearch.php?q=($q)&fl[]=identifier,title,mediatype,item_size,description" +
            "&rows=24&page=1&output=json"
        val body = httpGet(url, timeoutMs) ?: return emptyList()
        val result = mutableListOf<DirectFile>()
        try {
            val docs = JSONObject(body).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
            val items = mutableListOf<Triple<String, String, String>>() // id, title, mediatype
            for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                val id = d.optString("identifier").ifBlank { continue }
                items.add(Triple(id, d.optString("title", id), d.optString("mediatype", "")))
            }
            // файлы и форматы забираем параллельно — иначе 24 записи ждут друг друга
            val bag = java.util.Collections.synchronizedList(result)
            val queue = java.util.concurrent.LinkedBlockingQueue(items)
            val workers = (1..6).map {
                Thread({
                    while (true) {
                        val (id, title, mediaType) = queue.poll() ?: break
                        try {
                            bag.addAll(filesOfItem(id, title, mediaType, timeoutMs))
                        } catch (_: Throwable) {
                        }
                    }
                }, "DirectArchive").also { it.isDaemon = true; it.start() }
            }
            workers.forEach { it.join((timeoutMs + 4000).toLong()) }
        } catch (_: Throwable) {
        }
        return result
    }

    private fun filesOfItem(id: String, title: String, mediaType: String, timeoutMs: Int): List<DirectFile> {
        val body = httpGet("https://archive.org/metadata/$id", timeoutMs) ?: return emptyList()
        val json = JSONObject(body)
        val files = json.optJSONArray("files") ?: return emptyList()
        val kind = TorrentSearchAgent.kindForArchive(title, mediaType)
        val out = mutableListOf<DirectFile>()
        var main: DirectFile? = null
        var biggest = -1L
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val name = f.optString("name").ifBlank { continue }
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isBlank() || ext in JUNK_EXT) continue
            val size = f.optString("size").toLongOrNull() ?: 0L
            if (size in 1..20_000L) continue
            val direct = "https://archive.org/download/$id/" +
                name.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val item = DirectFile(
                title = title,
                source = "Archive.org",
                pageUrl = "https://archive.org/details/$id",
                downloadUrl = direct,
                fileName = name.substringAfterLast('/'),
                format = formatOf(name),
                sizeLabel = if (size > 0) BitTorrentEngine.formatSize(size) else "размер неизвестен",
                poster = "https://archive.org/services/img/$id",
                kind = kind,
                description = "Archive.org · $id"
            )
            if (ext in USEFUL_EXT) {
                if (out.size < 3) out.add(item)
                if (size > biggest) {
                    biggest = size
                    main = item
                }
            }
        }
        // самое крупное и полезное — первым
        val sorted = out.sortedByDescending { parseSize(it.sizeLabel) }
        return if (sorted.isNotEmpty()) sorted else listOfNotNull(main)
    }

    private fun parseSize(label: String): Long {
        val m = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*(ГБ|МБ|КБ|Б)""").find(label) ?: return 0
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0
        return when (m.groupValues[2]) {
            "ГБ" -> (v * 1024 * 1024 * 1024).toLong()
            "МБ" -> (v * 1024 * 1024).toLong()
            "КБ" -> (v * 1024).toLong()
            else -> v.toLong()
        }
    }

    /* ===================== GitHub Releases ===================== */

    private fun searchGitHub(query: String, timeoutMs: Int): List<DirectFile> {
        val q = URLEncoder.encode("$query in:name,description", "UTF-8")
        val body = httpGet("https://api.github.com/search/repositories?q=$q&sort=stars&per_page=6", timeoutMs)
            ?: return emptyList()
        val out = mutableListOf<DirectFile>()
        try {
            val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
            for (i in 0 until items.length()) {
                if (out.size >= 8) break
                val repo = items.getJSONObject(i)
                val full = repo.optString("full_name").ifBlank { continue }
                val desc = repo.optString("description", "")
                val stars = repo.optInt("stargazers_count", 0)
                val avatar = repo.optJSONObject("owner")?.optString("avatar_url", "").orEmpty()
                val rel = httpGet("https://api.github.com/repos/$full/releases/latest", timeoutMs) ?: continue
                val relJson = try {
                    JSONObject(rel)
                } catch (_: Throwable) {
                    continue
                }
                val assets = relJson.optJSONArray("assets") ?: continue
                for (a in 0 until assets.length()) {
                    if (out.size >= 8) break
                    val asset = assets.getJSONObject(a)
                    val name = asset.optString("name").ifBlank { continue }
                    val url = asset.optString("browser_download_url").ifBlank { continue }
                    val size = asset.optLong("size", 0L)
                    out.add(
                        DirectFile(
                            title = full,
                            source = "GitHub",
                            pageUrl = "https://github.com/$full",
                            downloadUrl = url,
                            fileName = name,
                            format = formatOf(name),
                            sizeLabel = if (size > 0) BitTorrentEngine.formatSize(size) else "размер неизвестен",
                            poster = avatar,
                            kind = TorrentSearchAgent.classify(name, source = "github"),
                            description = "★ $stars · " + desc.take(60)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    /* ===================== мелочи ===================== */

    /** Формат файла — то, что человек и спрашивает: XCI, EXE, EPUB, FLAC… */
    fun formatOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").uppercase()
        return when (ext) {
            "" -> "FILE"
            "APPIMAGE" -> "APPIMAGE"
            "TORRENT" -> "TORRENT"
            else -> if (ext.length <= 5) ext else ext.take(5)
        }
    }

    private fun httpGet(url: String, timeoutMs: Int): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OmniBrowser/4.47 (+direct-search)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Throwable) {
            null
        }
    }
}
