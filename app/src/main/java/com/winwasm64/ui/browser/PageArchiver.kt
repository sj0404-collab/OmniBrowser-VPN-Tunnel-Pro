package com.winwasm64.ui.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import com.winwasm64.vpn.VpnLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v4.5.0: сохранение страницы целиком (MHTML — текст, картинки, стили в одном файле) для чтения офлайн.
 * Файлы лежат в Android/data/<пакет>/files/pages (видны файловым менеджерам), открываются той же вкладкой через file://.
 */
object PageArchiver {
    private const val TAG = "Страницы"

    fun dir(ctx: Context): File = (ctx.getExternalFilesDir("pages") ?: File(ctx.filesDir, "pages")).apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile && it.name.endsWith(".mht") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun save(ctx: Context, wv: WebView, title: String, url: String, onDone: (File?) -> Unit) {
        val host = try { Uri.parse(url).host ?: "" } catch (_: Exception) { "" }
        val stem = MediaGrabber.safeName(title.ifBlank { host.ifBlank { "page" } }, "page").take(80)
        val name = stem + "_" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".mht"
        val f = File(dir(ctx), name)
        try {
            wv.saveWebArchive(f.absolutePath, false) { path ->
                if (path == null || !f.exists() || f.length() == 0L) {
                    f.delete()
                    VpnLog.w(TAG, "не удалось сохранить «${title.take(60)}» ($host)")
                    onDone(null)
                } else {
                    VpnLog.ok(TAG, "сохранено офлайн: «${title.take(60)}» → pages/$name (${MediaGrabber.fmtSize(f.length())})")
                    onDone(f)
                }
            }
        } catch (e: Exception) {
            VpnLog.w(TAG, "saveWebArchive: ${e.message}")
            onDone(null)
        }
    }

    fun fileUrl(f: File): String = Uri.fromFile(f).toString()
}
