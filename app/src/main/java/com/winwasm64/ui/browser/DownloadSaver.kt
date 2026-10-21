package com.winwasm64.ui.browser

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.security.SecureRandom

/** Сохранение файлов в общую папку «Загрузки» (MediaStore на Android 10+). */
object DownloadSaver {
    private val badChars = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")

    fun sanitize(name: String): String {
        val clean = name.replace(badChars, "_").trim().trim('.')
        return clean.take(120).ifBlank { "download.bin" }
    }

    fun withExtension(name: String, mime: String?): String {
        if (name.contains('.')) return name
        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it.substringBefore(';').trim()) }
        return if (ext.isNullOrBlank()) name else "$name.$ext"
    }

    /** Возвращает человекочитаемое место сохранения. */
    @Throws(IOException::class)
    fun saveBytes(context: Context, fileName: String, mime: String?, bytes: ByteArray): String {
        val type = mime?.substringBefore(';')?.trim()?.ifBlank { null } ?: "application/octet-stream"
        val name = sanitize(withExtension(fileName, type))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, type)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Система не дала создать файл в «Загрузках»")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("Нет доступа к файлу")
                out.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
                throw IOException(e.message ?: "Ошибка записи", e)
            }
            return "Загрузки/$name"
        }
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Нет доступа к папке «Загрузки»")
        var file = File(dir, name)
        var n = 1
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        while (file.exists()) {
            file = File(dir, "$base ($n)$ext")
            n++
        }
        file.writeBytes(bytes)
        return file.absolutePath
    }

    /** Разбор data:-ссылки: data:[<mime>][;base64],<данные>. */
    fun decodeDataUrl(url: String): Pair<String, ByteArray>? {
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val meta = url.substring(5, comma)
        val payload = url.substring(comma + 1)
        val isBase64 = meta.endsWith(";base64", ignoreCase = true)
        val mime = meta.removeSuffix(";base64").removeSuffix(";BASE64").substringBefore(';').ifBlank { "text/plain" }
        val bytes = if (isBase64) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").toByteArray(Charsets.UTF_8)
        }
        return mime to bytes
    }
}

/**
 * Мост для blob:-загрузок: страница читает blob через fetch и передаёт base64 сюда.
 * Принимаются только вызовы с одноразовым токеном, который знает лишь наш собственный скрипт.
 */
class BlobDownloadBridge(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, String>()
    private val random = SecureRandom()

    private fun newToken(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** JS-код, который нужно выполнить в странице, чтобы сохранить blob:-ссылку. */
    fun scriptFor(blobUrl: String, fileName: String): String {
        val token = newToken()
        synchronized(pending) { pending[token] = fileName }
        return """
            (function(){
              var u=${JSONObject.quote(blobUrl)}, t=${JSONObject.quote(token)};
              fetch(u).then(function(r){ return r.blob(); }).then(function(b){
                var fr = new FileReader();
                fr.onload = function(){ var s = String(fr.result); OmniBlobSaver.save(t, b.type || '', s.substring(s.indexOf(',') + 1)); };
                fr.onerror = function(){ OmniBlobSaver.fail(t, 'FileReader'); };
                fr.readAsDataURL(b);
              }).catch(function(e){ OmniBlobSaver.fail(t, String(e && e.message || e)); });
            })();
        """.trimIndent()
    }

    private fun toast(text: String) {
        main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }

    @JavascriptInterface
    fun save(token: String, mime: String, base64: String) {
        val name = synchronized(pending) { pending.remove(token) } ?: return
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val where = DownloadSaver.saveBytes(context, name, mime, bytes)
            toast("Сохранено: $where")
        } catch (e: Exception) {
            toast("Не удалось сохранить файл: ${e.message}")
        }
    }

    @JavascriptInterface
    fun fail(token: String, message: String) {
        if (synchronized(pending) { pending.remove(token) } == null) return
        toast("Не удалось скачать файл: $message")
    }
}
