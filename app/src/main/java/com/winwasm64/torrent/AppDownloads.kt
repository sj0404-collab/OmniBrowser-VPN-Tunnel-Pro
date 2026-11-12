package com.winwasm64.torrent

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * v4.49.0: единый журнал загрузок приложения.
 *
 * Была настоящая неразбериха: «Скачать» в «Файлах» и загрузка из браузера уходили в ГЛОБАЛЬНУЮ папку
 * Download — файл появлялся только в уведомлении системного загрузчика, а во вкладке «Загрузки»
 * и в папке приложения было пусто. Теперь:
 *  • всё качается в одну папку приложения — Download/OmniBrowser;
 *  • каждая загрузка регистрируется (id системного загрузчика), поэтому её видно во вкладке «Загрузки»
 *    с прогрессом, а не только в шторке;
 *  • «браузерная» загрузка и «прямая ссылка» идут одним путём.
 */
object AppDownloads {

    /** Подпапка внутри общей папки «Загрузки» — её же сканирует вкладка «Загрузки». */
    const val SUBDIR = "OmniBrowser"

    private const val PREFS = "omni_downloads"
    private const val KEY_IDS = "ids"

    data class Item(
        val id: Long,
        val name: String,
        val url: String,
        val bytes: Long,
        val total: Long,
        val status: Int,
        val localUri: String,
        val reason: Int
    ) {
        val progress: Int
            get() = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
        val isDone: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
        val isFailed: Boolean get() = status == DownloadManager.STATUS_FAILED
        val isActive: Boolean get() = status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING ||
            status == DownloadManager.STATUS_PAUSED
    }

    /** Поставить загрузку и запомнить её, чтобы показать во вкладке «Загрузки». */
    fun enqueue(
        context: Context,
        url: String,
        fileName: String,
        mimeType: String? = null,
        userAgent: String? = null,
        cookie: String? = null,
        referer: String? = null
    ): Long? {
        return try {
            val safe = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                if (!cookie.isNullOrBlank()) addRequestHeader("Cookie", cookie)
                if (!referer.isNullOrBlank()) addRequestHeader("Referer", referer)
                setTitle(safe)
                setDescription("OmniBrowser · $SUBDIR")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // ВАЖНО: своя подпапка приложения, а не общий котёл Download
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$SUBDIR/$safe")
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)
            remember(context, id)
            id
        } catch (_: Throwable) {
            null
        }
    }

    private fun remember(context: Context, id: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(id.toString())
        // держим только 200 последних, чтобы pref не разрастался
        val trimmed = current.sortedByDescending { it.toLongOrNull() ?: 0L }.take(200).toSet()
        prefs.edit().putStringSet(KEY_IDS, trimmed).apply()
    }

    /** Все загрузки приложения: активные сверху, потом завершённые. */
    fun list(context: Context): List<Item> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(KEY_IDS, emptySet())?.mapNotNull { it.toLongOrNull() } ?: return emptyList()
        if (ids.isEmpty()) return emptyList()
        val out = mutableListOf<Item>()
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val cursor = dm.query(DownloadManager.Query().setFilterById(*ids.toLongArray())) ?: return emptyList()
            cursor.use { c ->
                while (c.moveToNext()) {
                    fun col(name: String, fallback: Int) = c.getColumnIndex(name).takeIf { it >= 0 } ?: fallback
                    val id = c.getLong(col(DownloadManager.COLUMN_ID, 0))
                    val name = c.getString(col(DownloadManager.COLUMN_TITLE, 1)).orEmpty()
                    val uri = c.getString(col(DownloadManager.COLUMN_URI, 2)).orEmpty()
                    val bytes = c.getLong(col(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR, 3))
                    val total = c.getLong(col(DownloadManager.COLUMN_TOTAL_SIZE_BYTES, 4))
                    val status = c.getInt(col(DownloadManager.COLUMN_STATUS, 5))
                    val local = c.getString(col(DownloadManager.COLUMN_LOCAL_URI, 6)).orEmpty()
                    val reason = c.getInt(col(DownloadManager.COLUMN_REASON, 7))
                    out.add(Item(id, name, uri, bytes, total, status, local, reason))
                }
            }
        } catch (_: Throwable) {
        }
        return out.sortedWith(compareByDescending<Item> { it.isActive }.thenByDescending { it.id })
    }

    /** Папка приложения, куда всё складывается. */
    /**
     * v4.68.0: «не могу открыть файл» и «вот тут кнопки продолжить либо повторить».
     *
     * Скачанное лежит в общей папке `Загрузки/OmniBrowser`; чтобы открыть его из приложения,
     * нужно найти реальный файл. DownloadManager может отдать `content://`-адрес, поэтому
     * пробуем несколько путей: локальный URI, затем папку приложения по имени.
     */
    fun fileOf(context: Context, item: Item): File? {
        val name = item.name.ifBlank { "файл ${item.id}" }
        val candidates = mutableListOf<File>()
        // 1) папка приложения
        candidates.add(File(folder(), name))
        // 2) общая папка «Загрузки» (так ставит enqueue)
        try {
            val ext = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            candidates.add(File(File(ext, SUBDIR), name))
        } catch (_: Throwable) {
        }
        // 3) путь из DownloadManager, если он файловый
        try {
            if (item.localUri.startsWith("file://")) {
                candidates.add(File(java.net.URI(item.localUri)))
            }
        } catch (_: Throwable) {
        }
        return candidates.firstOrNull { it.exists() && it.length() > 0 }
    }

    /** Повторить загрузку с нуля (кнопка «Повторить» у ошибки). */
    fun retry(context: Context, item: Item): Long? {
        if (item.url.isBlank()) return null
        try {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(item.id)
        } catch (_: Throwable) {
        }
        return enqueue(context, item.url, item.name)
    }

    /**
     * Продолжить приостановленную загрузку. Публичного API для этого нет — дергаем скрытый
     * `resumeDownload(long...)`; если прошивка не даёт, честно перезапускаем заново.
     */
    fun resume(context: Context, item: Item): Boolean {
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val m = DownloadManager::class.java.getMethod("resumeDownload", LongArray::class.java)
            m.invoke(dm, longArrayOf(item.id))
            true
        } catch (_: Throwable) {
            retry(context, item) != null
        }
    }

    /** Убрать запись и файл (кнопка «Удалить»). */
    fun remove(context: Context, item: Item, deleteFile: Boolean = true) {
        try {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(item.id)
        } catch (_: Throwable) {
        }
        if (deleteFile) {
            try { fileOf(context, item)?.delete() } catch (_: Throwable) {}
        }
        unremember(context, item.id)
    }

    private fun unremember(context: Context, id: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(id.toString())
        prefs.edit().putStringSet(KEY_IDS, current).apply()
    }

    fun folder(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
}
