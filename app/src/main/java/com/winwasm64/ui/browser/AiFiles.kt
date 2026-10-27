package com.winwasm64.ui.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import com.winwasm64.vpn.VpnLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * v4.8.0: файлы в чате + локальный RAG. Текст извлекается на устройстве (txt/код/html/pdf/docx/pptx/xlsx/epub/zip…),
 * режется на фрагменты и ищется по BM25 — объём файлов не ограничен контекстом модели: в запрос попадают только
 * релевантные фрагменты, а модель может дочитать остальное инструментами search_files / read_file.
 * Фото, сканы PDF и кадры видео уходят моделям со «зрением» (data:image/jpeg;base64), речь из аудио/видео —
 * в распознавание (Groq whisper / api.navy по ключу).
 */
object AiFiles {
    private const val CHUNK = 1500
    private const val OVERLAP = 150
    private const val MAX_TEXT = 40_000_000
    private const val MAX_IMG_PX = 1280

    class Doc(
        val id: String, val name: String, val mime: String, val size: Long, val kind: String,
        var summary: String, val chunks: MutableList<String>, val images: MutableList<String>, var mediaPath: String = ""
    ) {
        val textLength get() = chunks.sumOf { it.length }
    }

    /** chatId → документы */
    val docs = mutableStateOf<Map<String, List<Doc>>>(emptyMap())
    val processing = mutableStateOf("")
    private var loaded = false

    private fun dir(ctx: Context) = File(ctx.filesDir, "ai/docs").also { it.mkdirs() }
    private fun imgDir(ctx: Context) = File(ctx.filesDir, "ai/img").also { it.mkdirs() }
    private fun mediaDir(ctx: Context) = File(ctx.filesDir, "ai/media").also { it.mkdirs() }

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val m = LinkedHashMap<String, List<Doc>>()
        try {
            for (f in dir(ctx).listFiles() ?: emptyArray()) {
                if (!f.name.endsWith(".json")) continue
                val arr = JSONArray(f.readText())
                val list = ArrayList<Doc>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ch = o.getJSONArray("chunks"); val im = o.optJSONArray("images") ?: JSONArray()
                    list.add(Doc(o.getString("id"), o.getString("name"), o.optString("mime"), o.optLong("size"), o.optString("kind"), o.optString("summary"),
                        MutableList(ch.length()) { ch.getString(it) }, MutableList(im.length()) { im.getString(it) }, o.optString("media")))
                }
                m[f.name.removeSuffix(".json")] = list
            }
        } catch (_: Exception) {}
        docs.value = m
    }

    private fun save(ctx: Context, chatId: String) {
        val list = docs.value[chatId] ?: emptyList()
        val f = File(dir(ctx), "$chatId.json")
        if (list.isEmpty()) { f.delete(); return }
        val arr = JSONArray()
        for (d in list) arr.put(JSONObject().put("id", d.id).put("name", d.name).put("mime", d.mime).put("size", d.size).put("kind", d.kind).put("summary", d.summary)
            .put("chunks", JSONArray(d.chunks)).put("images", JSONArray(d.images)).put("media", d.mediaPath))
        Thread({ try { f.writeText(arr.toString()) } catch (_: Exception) {} }, "omni-ai-docs").start()
    }

    fun of(chatId: String): List<Doc> = docs.value[chatId] ?: emptyList()

    fun remove(ctx: Context, chatId: String, d: Doc) {
        d.images.forEach { File(it).delete() }
        if (d.mediaPath.isNotBlank()) File(d.mediaPath).delete()
        docs.value = docs.value + (chatId to of(chatId).filter { it !== d })
        save(ctx, chatId)
    }

    fun removeChat(ctx: Context, chatId: String) { of(chatId).toList().forEach { remove(ctx, chatId, it) } }

    // ------------------------------------------------------------------ добавление
    fun attach(ctx: Context, chatId: String, uris: List<Uri>, onDone: (List<Doc>) -> Unit) {
        val app = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            val added = ArrayList<Doc>()
            for ((i, u) in uris.withIndex()) {
                val (name, size) = meta(app, u)
                main.post { processing.value = "Обрабатываю ${i + 1}/${uris.size}: $name" }
                try {
                    val d = extract(app, u, name, size, app.contentResolver.getType(u) ?: "")
                    added.add(d)
                    VpnLog.i(AiAssistant.TAG, "файл «${d.name}» (${d.kind}, ${d.size / 1024} КБ): ${d.summary.take(120)}")
                } catch (e: Exception) {
                    VpnLog.w(AiAssistant.TAG, "файл «$name»: ${e.message}")
                    added.add(Doc(newId(), name, "", size, "other", "не удалось прочитать: ${e.message}", mutableListOf(), mutableListOf()))
                }
            }
            main.post {
                docs.value = docs.value + (chatId to of(chatId) + added)
                save(app, chatId)
                processing.value = ""
                onDone(added)
            }
        }, "omni-ai-attach").start()
    }

    private fun newId() = System.currentTimeMillis().toString(36) + (0..999).random()

    private fun meta(ctx: Context, u: Uri): Pair<String, Long> {
        var name = u.lastPathSegment ?: "файл"; var size = 0L
        try {
            ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (ni >= 0) c.getString(ni)?.let { name = it }
                    val si = c.getColumnIndex(OpenableColumns.SIZE); if (si >= 0) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {}
        return name to size
    }

    private fun open(ctx: Context, u: Uri): InputStream = ctx.contentResolver.openInputStream(u) ?: throw IllegalStateException("нет доступа к файлу")

    private val textExt = setOf("txt", "md", "csv", "tsv", "json", "xml", "yaml", "yml", "ini", "cfg", "conf", "log", "srt", "vtt", "ass", "html", "htm", "css", "js", "ts", "jsx", "tsx", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "sh", "bat", "ps1", "sql", "gradle", "properties", "toml", "tex", "rtf", "env", "lua", "swift", "dart", "m", "r", "pl", "nfo", "diff", "patch")

    private fun extract(ctx: Context, u: Uri, name: String, size: Long, mime0: String): Doc {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = mime0.lowercase()
        val id = newId()
        return when {
            mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic") -> image(ctx, u, id, name, size, mime)
            mime == "application/pdf" || ext == "pdf" -> pdf(ctx, u, id, name, size)
            ext in setOf("docx", "pptx", "xlsx", "odt", "odp", "ods", "epub") -> office(ctx, u, id, name, size, ext)
            mime.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr", "oga") -> audio(ctx, u, id, name, size, mime)
            mime.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v") -> video(ctx, u, id, name, size, mime)
            ext in setOf("zip", "jar", "apk", "aar", "xpi", "crx", "cbz") || mime == "application/zip" -> zip(ctx, u, id, name, size)
            ext in setOf("rar", "7z", "tar", "gz", "tgz", "bz2", "xz") -> Doc(id, name, mime, size, "archive", "архив формата .$ext — читаю только zip; распакуйте и прикрепите файлы", mutableListOf(), mutableListOf())
            mime.startsWith("text/") || ext in textExt || mime.contains("json") || mime.contains("xml") || mime.contains("javascript") -> text(ctx, u, id, name, size, mime, ext)
            else -> {
                val head = open(ctx, u).use { it.readNBytesCompat(4096) }
                val printable = head.count { val b = it.toInt(); b in 0x20..0x7e || b == 0x0a || b == 0x0d || b == 0x09 || b < 0 }
                if (head.isNotEmpty() && printable > head.size * 0.9) text(ctx, u, id, name, size, mime, ext)
                else Doc(id, name, mime, size, "other", "двоичный файл (${size / 1024} КБ, сигнатура ${head.take(8).joinToString(" ") { "%02x".format(it) }}) — текст извлечь нельзя", mutableListOf(), mutableListOf())
            }
        }
    }

    private fun InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n); var off = 0
        while (off < n) { val r = read(buf, off, n - off); if (r <= 0) break; off += r }
        return buf.copyOf(off)
    }

    private fun readAllLimited(ins: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(); val b = ByteArray(65536); var total = 0
        ins.use { s -> while (true) { val r = s.read(b); if (r <= 0) break; out.write(b, 0, r); total += r; if (total >= limit) break } }
        return out.toByteArray()
    }

    private fun decodeText(bytes: ByteArray): String {
        var s = String(bytes, Charsets.UTF_8)
        if (s.count { it == '\uFFFD' } > bytes.size / 200) s = String(bytes, charset("windows-1251"))
        return s
    }

    private fun text(ctx: Context, u: Uri, id: String, name: String, size: Long, mime: String, ext: String): Doc {
        var s = decodeText(readAllLimited(open(ctx, u), MAX_TEXT))
        if (ext == "html" || ext == "htm" || mime.contains("html")) s = stripHtml(s)
        if (ext == "srt" || ext == "vtt") s = s.replace(Regex("(?m)^\\d+\\s*$"), "").replace(Regex("\\d{2}:\\d{2}:\\d{2}[,.]\\d{3} --> .*"), "").replace(Regex("\\n{2,}"), "\n")
        val ch = chunk(s)
        return Doc(id, name, mime, size, "text", "текст: ${s.length} знаков, ${s.count { it == '\n' } + 1} строк, фрагментов ${ch.size}", ch.toMutableList(), mutableListOf())
    }

    fun stripHtml(html: String): String {
        var s = html.replace(Regex("(?is)<(script|style|noscript|svg|template)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>|</tr>|</title>"), "\n").replace(Regex("<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        return s.replace(Regex("[ \\t\\r\\x0B\\f]+"), " ").replace(Regex("\\s*\\n\\s*"), "\n").trim()
    }

    private fun image(ctx: Context, u: Uri, id: String, name: String, size: Long, mime: String): Doc {
        val path = saveScaled(ctx, open(ctx, u), id, 0) ?: throw IllegalStateException("не удалось декодировать изображение")
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(ctx, u).use { BitmapFactory.decodeStream(it, null, o) }
        return Doc(id, name, mime, size, "image", "изображение ${o.outWidth}×${o.outHeight} — для анализа нужна модель со зрением", mutableListOf(), mutableListOf(path))
    }

    /** Уменьшенная JPEG-копия (≤1280 px) для отправки модели. */
    private fun saveScaled(ctx: Context, ins: InputStream, id: String, idx: Int): String? {
        val bytes = readAllLimited(ins, 60_000_000)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / sample > MAX_IMG_PX * 2) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return saveBitmap(ctx, bmp, id, idx)
    }

    private fun saveBitmap(ctx: Context, bmp0: Bitmap, id: String, idx: Int): String {
        var bmp = bmp0
        val m = max(bmp.width, bmp.height)
        if (m > MAX_IMG_PX) { val k = MAX_IMG_PX.toFloat() / m; bmp = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).toInt()), max(1, (bmp.height * k).toInt()), true) }
        val f = File(imgDir(ctx), "${id}_$idx.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        return f.absolutePath
    }

    fun imageDataUrl(path: String): String? = try { "data:image/jpeg;base64," + Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP) } catch (_: Exception) { null }

    // ------------------------------------------------------------------ PDF
    private fun pdf(ctx: Context, u: Uri, id: String, name: String, size: Long): Doc {
        val bytes = readAllLimited(open(ctx, u), MAX_TEXT)
        val text = try { pdfText(bytes) } catch (_: Exception) { "" }
        val pages = Regex("/Type\\s*/Page[^s]").findAll(String(bytes, Charsets.ISO_8859_1)).count().coerceAtLeast(1)
        val images = ArrayList<String>()
        var summary: String
        if (text.length < 80 * pages) {
            // скан или нестандартные шрифты — рендерим страницы для модели со зрением
            try {
                val tmp = File(mediaDir(ctx), "$id.pdf"); tmp.writeBytes(bytes)
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { r ->
                        for (i in 0 until min(r.pageCount, 12)) {
                            r.openPage(i).use { pg ->
                                val k = min(1.0f, 1100f / pg.width)
                                val bmp = Bitmap.createBitmap(max(1, (pg.width * k).toInt()), max(1, (pg.height * k).toInt()), Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                images.add(saveBitmap(ctx, bmp, id, i))
                            }
                        }
                    }
                }
                tmp.delete()
            } catch (_: Exception) {}
            summary = "PDF, страниц ~$pages: текстовый слой ${if (text.isBlank()) "отсутствует" else "скудный (${text.length} зн.)"}; ${images.size} стр. переданы как изображения (нужна модель со зрением)"
        } else summary = "PDF, страниц ~$pages, текст ${text.length} знаков"
        return Doc(id, name, "application/pdf", size, "pdf", summary, chunk(text).toMutableList(), images)
    }

    /** Упрощённое извлечение текста из PDF: потоки FlateDecode → операторы Tj/TJ/'/". Работает для большинства «цифровых» PDF. */
    private fun pdfText(bytes: ByteArray): String {
        val raw = String(bytes, Charsets.ISO_8859_1)
        val sb = StringBuilder()
        var idx = 0
        while (true) {
            val s = raw.indexOf("stream", idx); if (s < 0) break
            var start = s + 6
            if (raw.startsWith("\r\n", start)) start += 2 else if (raw.startsWith("\n", start)) start += 1
            val e = raw.indexOf("endstream", start); if (e < 0) break
            idx = e + 9
            val dictStart = raw.lastIndexOf("<<", s).coerceAtLeast(0)
            val dict = raw.substring(dictStart, s)
            if (dict.contains("/Image") || dict.contains("/FontFile") || dict.contains("/ObjStm")) continue
            var data = bytes.copyOfRange(start, e)
            if (dict.contains("FlateDecode")) {
                data = try { inflate(data) } catch (_: Exception) { continue }
            } else if (dict.contains("/Filter")) continue
            val content = String(data, Charsets.ISO_8859_1)
            if (!content.contains("Tj") && !content.contains("TJ")) continue
            extractTextOps(content, sb)
            if (sb.length > MAX_TEXT) break
        }
        return sb.toString().replace(Regex("[ \\t]{2,}"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater(); inf.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4); val buf = ByteArray(65536)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0) { if (inf.needsInput() || inf.needsDictionary()) break }; out.write(buf, 0, n); if (out.size() > 50_000_000) break }
        inf.end()
        return out.toByteArray()
    }

    private fun extractTextOps(c: String, sb: StringBuilder) {
        var i = 0; val n = c.length
        var lineStart = sb.length
        fun pdfString(from: Int): Pair<String, Int> {
            val b = StringBuilder(); var depth = 1; var j = from
            while (j < n && depth > 0) {
                val ch = c[j]
                when {
                    ch == '\\' && j + 1 < n -> {
                        val nx = c[j + 1]
                        when (nx) { 'n' -> b.append('\n'); 'r' -> {}; 't' -> b.append(' '); 'b', 'f' -> {}; '(' -> b.append('('); ')' -> b.append(')'); '\\' -> b.append('\\')
                            else -> if (nx.isDigit()) { var k = j + 1; var oct = 0; var cnt = 0; while (k < n && cnt < 3 && c[k].isDigit()) { oct = oct * 8 + (c[k] - '0'); k++; cnt++ }; b.append(oct.toChar()); j = k - 2 } }
                        j += 2
                    }
                    ch == '(' -> { depth++; b.append(ch); j++ }
                    ch == ')' -> { depth--; if (depth > 0) b.append(ch); j++ }
                    else -> { b.append(ch); j++ }
                }
            }
            return b.toString() to j
        }
        fun hexString(from: Int): Pair<String, Int> {
            val end = c.indexOf('>', from).let { if (it < 0) n else it }
            val hex = c.substring(from, end).replace(Regex("\\s"), "")
            val b = StringBuilder()
            var k = 0
            // 2-байтовые коды (CID) часто совпадают с UTF-16BE для простых шрифтов; иначе 1 байт
            if (hex.length % 4 == 0 && hex.length >= 4) { while (k + 4 <= hex.length) { val v = hex.substring(k, k + 4).toIntOrNull(16) ?: 0; if (v in 0x20..0xFFFD) b.append(v.toChar()); k += 4 } }
            else while (k + 2 <= hex.length) { val v = hex.substring(k, k + 2).toIntOrNull(16) ?: 0; if (v >= 0x20) b.append(v.toChar()); k += 2 }
            return b.toString() to end + 1
        }
        while (i < n) {
            val ch = c[i]
            when {
                ch == '(' -> { val (s, j) = pdfString(i + 1); sb.append(s); i = j }
                ch == '<' && i + 1 < n && c[i + 1] != '<' -> { val (s, j) = hexString(i + 1); sb.append(s); i = j }
                ch == 'T' && i + 1 < n && (c[i + 1] == '*' || c[i + 1] == 'd' || c[i + 1] == 'D' || c[i + 1] == 'm') -> { if (sb.length > lineStart) { sb.append('\n'); lineStart = sb.length }; i += 2 }
                ch == '\'' || ch == '"' -> { sb.append('\n'); lineStart = sb.length; i++ }
                ch == 'E' && c.startsWith("ET", i) -> { if (sb.length > lineStart) { sb.append('\n'); lineStart = sb.length }; i += 2 }
                ch == '-' || ch.isDigit() -> {
                    // число внутри TJ-массива: большой отрицательный сдвиг = пробел
                    var j = i; while (j < n && (c[j] == '-' || c[j] == '.' || c[j].isDigit())) j++
                    val v = c.substring(i, j).toDoubleOrNull()
                    if (v != null && v < -180 && j < n && (c[j] == '(' || c[j] == '<' || c[j] == ' ')) sb.append(' ')
                    i = if (j > i) j else i + 1
                }
                else -> i++
            }
        }
    }

    // ------------------------------------------------------------------ Office / EPUB / ZIP
    private fun office(ctx: Context, u: Uri, id: String, name: String, size: Long, ext: String): Doc {
        val parts = LinkedHashMap<String, String>()
        ZipInputStream(open(ctx, u)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name
                val want = when (ext) {
                    "docx" -> n == "word/document.xml" || n.startsWith("word/footnotes") || n.startsWith("word/endnotes")
                    "pptx" -> n.matches(Regex("ppt/slides/slide\\d+\\.xml")) || n.matches(Regex("ppt/notesSlides/notesSlide\\d+\\.xml"))
                    "xlsx" -> n == "xl/sharedStrings.xml" || n.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))
                    "epub" -> n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")
                    else -> n == "content.xml"
                }
                if (want) parts[n] = decodeText(readAllLimited(object : InputStream() { override fun read() = z.read(); override fun read(b: ByteArray, o: Int, l: Int) = z.read(b, o, l); override fun close() {} }, MAX_TEXT))
            }
        }
        val sb = StringBuilder()
        when (ext) {
            "xlsx" -> {
                val shared = ArrayList<String>()
                parts["xl/sharedStrings.xml"]?.let { ss -> Regex("(?s)<si>(.*?)</si>").findAll(ss).forEach { m -> shared.add(Regex("(?s)<t[^>]*>(.*?)</t>").findAll(m.groupValues[1]).joinToString("") { it.groupValues[1] }) } }
                for ((n, xml) in parts.toSortedMap()) {
                    if (!n.startsWith("xl/worksheets/")) continue
                    sb.append("## ").append(n.substringAfterLast('/')).append('\n')
                    for (row in Regex("(?s)<row[^>]*>(.*?)</row>").findAll(xml)) {
                        val cells = Regex("(?s)<c([^>]*)>(.*?)</c>").findAll(row.groupValues[1]).map { c ->
                            val attrs = c.groupValues[1]; val inner = c.groupValues[2]
                            val v = Regex("(?s)<v>(.*?)</v>").find(inner)?.groupValues?.get(1) ?: Regex("(?s)<t[^>]*>(.*?)</t>").find(inner)?.groupValues?.get(1) ?: ""
                            if (attrs.contains("t=\"s\"")) shared.getOrNull(v.toIntOrNull() ?: -1) ?: v else v
                        }.toList()
                        if (cells.any { it.isNotBlank() }) sb.append(cells.joinToString(" | ")).append('\n')
                    }
                }
            }
            else -> for ((n, xml) in parts.toSortedMap(compareBy<String>({ it.length }, { it }))) {
                if (ext == "pptx") sb.append("## ").append(n.substringAfterLast('/').removeSuffix(".xml")).append('\n')
                val t = xml.replace(Regex("</w:p>|</a:p>|</text:p>|<w:br/>|</p>|<br/>|</h\\d>|</li>|</tr>"), "\n").replace(Regex("</w:tc>|</a:tc>|</td>"), " | ")
                sb.append(stripHtml(t)).append('\n')
            }
        }
        val text = sb.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
        val ch = chunk(text)
        return Doc(id, name, "", size, "text", "${ext.uppercase()}: ${text.length} знаков, фрагментов ${ch.size}", ch.toMutableList(), mutableListOf())
    }

    private fun zip(ctx: Context, u: Uri, id: String, name: String, size: Long): Doc {
        val listing = StringBuilder()
        val textParts = StringBuilder()
        var count = 0; var textBytes = 0L
        ZipInputStream(open(ctx, u)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                count++
                if (count <= 400) listing.append(e.name).append(if (e.size >= 0) " (${e.size} Б)" else "").append('\n')
                val ext = e.name.substringAfterLast('.', "").lowercase()
                if (!e.isDirectory && (ext in textExt) && textBytes < 3_000_000) {
                    val bytes = readAllLimited(object : InputStream() { override fun read() = z.read(); override fun read(b: ByteArray, o: Int, l: Int) = z.read(b, o, l); override fun close() {} }, 1_000_000)
                    textBytes += bytes.size
                    textParts.append("\n===== ").append(e.name).append(" =====\n").append(decodeText(bytes))
                }
            }
        }
        val text = "Содержимое архива $name ($count файлов):\n$listing" + (if (textParts.isNotEmpty()) "\n\nТекстовые файлы из архива:$textParts" else "")
        val ch = chunk(text)
        return Doc(id, name, "application/zip", size, "archive", "архив: $count файлов, извлечено текста ${textParts.length} знаков", ch.toMutableList(), mutableListOf())
    }

    // ------------------------------------------------------------------ аудио / видео
    private fun copyMedia(ctx: Context, u: Uri, id: String, ext: String): File {
        val f = File(mediaDir(ctx), "$id.$ext")
        open(ctx, u).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f
    }

    private fun audio(ctx: Context, u: Uri, id: String, name: String, size: Long, mime: String): Doc {
        val ext = name.substringAfterLast('.', "m4a").lowercase()
        val f = copyMedia(ctx, u, id, ext)
        val dur = durationOf(f)
        val d = Doc(id, name, mime, size, "audio", "аудио ${fmtDur(dur)}", mutableListOf(), mutableListOf(), f.absolutePath)
        transcribeInto(ctx, d, f)
        return d
    }

    private fun video(ctx: Context, u: Uri, id: String, name: String, size: Long, mime: String): Doc {
        val ext = name.substringAfterLast('.', "mp4").lowercase()
        val f = copyMedia(ctx, u, id, ext)
        val images = ArrayList<String>()
        var dur = 0L; var w = 0; var h = 0
        try {
            val r = MediaMetadataRetriever(); r.setDataSource(f.absolutePath)
            dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val n = when { dur < 20_000 -> 3; dur < 180_000 -> 6; else -> 8 }
            for (i in 0 until n) {
                val tUs = (dur * 1000L * (i + 0.5) / n).toLong()
                val bmp = r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                images.add(saveBitmap(ctx, bmp, id, i))
            }
            r.release()
        } catch (_: Exception) {}
        val d = Doc(id, name, mime, size, "video", "видео ${fmtDur(dur)}, ${w}×$h, кадров для анализа ${images.size}", mutableListOf(), images, f.absolutePath)
        // звуковая дорожка → отдельный m4a → распознавание
        try {
            val a = File(mediaDir(ctx), "${id}_audio.m4a")
            if (extractAudioTrack(f, a)) transcribeInto(ctx, d, a) else d.summary += "; звуковой дорожки нет"
            a.delete()
        } catch (e: Exception) { d.summary += "; звук не извлечён (${e.message})" }
        return d
    }

    private fun durationOf(f: File): Long = try { val r = MediaMetadataRetriever(); r.setDataSource(f.absolutePath); val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L; r.release(); d } catch (_: Exception) { 0L }
    private fun fmtDur(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "${s / 3600} ч ${(s % 3600) / 60} мин" else "${s / 60} мин ${s % 60} с" }

    /** Копирует аудиодорожку (AAC и т.п.) в m4a без перекодирования, до 25 МБ. */
    private fun extractAudioTrack(src: File, dst: File): Boolean {
        val ex = MediaExtractor(); ex.setDataSource(src.absolutePath)
        var track = -1
        for (i in 0 until ex.trackCount) { val f = ex.getTrackFormat(i); if (f.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break } }
        if (track < 0) { ex.release(); return false }
        val fmt = ex.getTrackFormat(track)
        val mimeA = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: ""
        if (!mimeA.contains("aac") && !mimeA.contains("mp4a")) { ex.release(); return false }
        ex.selectTrack(track)
        val mux = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val out = mux.addTrack(fmt); mux.start()
        val buf = ByteBuffer.allocate(1 shl 20); val info = android.media.MediaCodec.BufferInfo()
        var total = 0L
        while (true) {
            val n = ex.readSampleData(buf, 0); if (n < 0) break
            info.offset = 0; info.size = n; info.presentationTimeUs = ex.sampleTime; info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            mux.writeSampleData(out, buf, info); total += n
            if (total > 24_000_000) break
            ex.advance()
        }
        try { mux.stop() } catch (_: Exception) {}
        mux.release(); ex.release()
        return total > 0
    }

    private fun transcribeInto(ctx: Context, d: Doc, f: File) {
        val (text, note) = AiAssistant.transcribe(ctx, f)
        if (!text.isNullOrBlank()) {
            d.chunks.addAll(chunk("Расшифровка речи из «${d.name}»:\n$text"))
            d.summary += "; речь распознана: ${text.length} знаков"
        } else d.summary += "; речь не распознана: ${note ?: "нужен ключ Groq (whisper) или api.navy"}"
    }

    // ------------------------------------------------------------------ RAG
    fun chunk(text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var i = 0
        while (i < t.length) {
            var end = min(t.length, i + CHUNK)
            if (end < t.length) { val nl = t.lastIndexOf('\n', end); if (nl > i + CHUNK / 2) end = nl else { val sp = t.lastIndexOf(' ', end); if (sp > i + CHUNK / 2) end = sp } }
            out.add(t.substring(i, end).trim())
            if (end >= t.length) break
            i = max(end - OVERLAP, i + 1)
        }
        return out
    }

    private fun tokens(s: String): List<String> = Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { val w = it.value; if (w.length > 6) w.take(6) else w }.filter { it.length >= 2 }.toList()

    /** BM25-поиск по всем файлам чата. Возвращает подборку фрагментов до [maxChars]. */
    fun retrieve(chatId: String, query: String, maxChars: Int): String {
        val list = of(chatId).filter { it.chunks.isNotEmpty() }
        if (list.isEmpty()) return ""
        val q = tokens(query).distinct()
        if (q.isEmpty()) return ""
        class C(val doc: Doc, val idx: Int, val toks: List<String>)
        val all = ArrayList<C>()
        for (d in list) for ((i, c) in d.chunks.withIndex()) all.add(C(d, i, tokens(c)))
        val n = all.size
        val avg = all.sumOf { it.toks.size }.toDouble() / n
        val df = HashMap<String, Int>()
        for (c in all) for (t in c.toks.toHashSet()) if (t in q) df[t] = (df[t] ?: 0) + 1
        val scored = all.map { c ->
            val tf = HashMap<String, Int>(); for (t in c.toks) if (t in q) tf[t] = (tf[t] ?: 0) + 1
            var s = 0.0
            for (t in q) { val f = tf[t] ?: continue; val d = df[t] ?: 0; val idf = ln(1 + (n - d + 0.5) / (d + 0.5)); s += idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * c.toks.size / max(1.0, avg))) }
            c to s
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        val sb = StringBuilder()
        for ((c, s) in scored) {
            val piece = "[${c.doc.name} · фрагмент ${c.idx + 1}/${c.doc.chunks.size}]\n${c.doc.chunks[c.idx]}\n\n"
            if (sb.length + piece.length > maxChars) { if (sb.isEmpty()) sb.append(piece.take(maxChars)); break }
            sb.append(piece)
        }
        // если ничего не нашлось — даём начало каждого файла
        if (sb.isEmpty()) for (d in list) { val piece = "[${d.name} · начало]\n${d.chunks.first()}\n\n"; if (sb.length + piece.length > maxChars) break; sb.append(piece) }
        return sb.toString().trim()
    }

    fun listing(chatId: String): String = of(chatId).joinToString("\n") { d ->
        "• ${d.name} — ${d.summary}" + (if (d.chunks.isNotEmpty()) " · текст ${d.textLength} зн. (${d.chunks.size} фрагм.)" else "") + (if (d.images.isNotEmpty()) " · изображений ${d.images.size}" else "")
    }

    /** Последовательное чтение файла: фрагменты [from, from+count). */
    fun read(chatId: String, name: String, from: Int, count: Int): String {
        val d = of(chatId).firstOrNull { it.name.equals(name, true) } ?: of(chatId).firstOrNull { it.name.contains(name, true) } ?: return "Файл «$name» не найден. Есть: " + of(chatId).joinToString(", ") { it.name }
        if (d.chunks.isEmpty()) return "«${d.name}»: ${d.summary}. Текста нет."
        val f = from.coerceIn(0, d.chunks.size - 1); val to = min(d.chunks.size, f + max(1, count))
        return "«${d.name}», фрагменты ${f + 1}–$to из ${d.chunks.size}:\n\n" + (f until to).joinToString("\n\n") { "[${it + 1}] " + d.chunks[it] } + (if (to < d.chunks.size) "\n\n(дальше: read_file с from=$to)" else "\n\n(конец файла)")
    }

    /** Пути изображений чата (фото, сканы, кадры) — для моделей со зрением. */
    fun images(chatId: String): List<Pair<Doc, String>> = of(chatId).flatMap { d -> d.images.map { d to it } }

}
