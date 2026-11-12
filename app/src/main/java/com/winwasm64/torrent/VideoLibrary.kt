package com.winwasm64.torrent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v4.81.0: библиотека видео на устройстве — то, чего не хватало:
 *  • «Смотреть позже» (закладки) с обложками и каналом;
 *  • позиция просмотра для каждого ролика → плеер продолжает с того места, где остановились;
 *  • отметка «скачано» с путём к файлу → офлайн-просмотр без сети.
 *
 * Ключ записи — устойчивый ключ видео из [VideoFeed.keyOf] (YouTube-id / oid_id VK / hex-id RuTube),
 * поэтому одна и та же карточка из поиска, из канала и из подписок ведёт к одной записи.
 */
object VideoLibrary {

    private const val PREFS = "omni_video_library"
    private const val KEY = "items"

    data class Saved(
        val key: String,
        val title: String,
        val author: String,
        val source: String,
        val url: String,
        val thumb: String,
        val lengthText: String,
        val lengthSec: Int,
        val added: Long,
        /** сохранено в «Смотреть позже» */
        val saved: Boolean = true,
        /** позиция просмотра, мс */
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        /** локальный файл (скачано) */
        val localPath: String = ""
    ) {
        val progressPercent: Int
            get() = if (durationMs > 0L) ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100) else 0
        val watched: Boolean get() = progressPercent >= 95
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Saved> {
        val raw = prefs(ctx).getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val key = o.optString("key")
                if (key.isBlank()) return@mapNotNull null
                Saved(
                    key = key,
                    title = o.optString("title"),
                    author = o.optString("author"),
                    source = o.optString("source", VideoFeed.SRC_YOUTUBE),
                    url = o.optString("url"),
                    thumb = o.optString("thumb"),
                    lengthText = o.optString("lengthText"),
                    lengthSec = o.optInt("lengthSec"),
                    added = o.optLong("added"),
                    saved = o.optBoolean("saved", true),
                    positionMs = o.optLong("positionMs"),
                    durationMs = o.optLong("durationMs"),
                    localPath = o.optString("localPath")
                )
            }.sortedByDescending { it.added }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveAll(ctx: Context, items: List<Saved>) {
        val arr = JSONArray()
        for (s in items.take(500)) {
            arr.put(
                JSONObject()
                    .put("key", s.key).put("title", s.title).put("author", s.author).put("source", s.source)
                    .put("url", s.url).put("thumb", s.thumb).put("lengthText", s.lengthText)
                    .put("lengthSec", s.lengthSec).put("added", s.added).put("saved", s.saved)
                    .put("positionMs", s.positionMs).put("durationMs", s.durationMs).put("localPath", s.localPath)
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun upsert(ctx: Context, key: String, change: (Saved?) -> Saved?) {
        val cur = list(ctx).toMutableList()
        val idx = cur.indexOfFirst { it.key == key }
        val old = if (idx >= 0) cur[idx] else null
        val next = change(old)
        if (idx >= 0) cur.removeAt(idx)
        if (next != null) cur.add(0, next)
        // записи без закладки, без прогресса и без файла не храним
        val clean = cur.filter { it.saved || it.positionMs > 0L || it.localPath.isNotBlank() }
        saveAll(ctx, clean)
    }

    fun find(ctx: Context, key: String): Saved? = list(ctx).firstOrNull { it.key == key }

    fun isSaved(ctx: Context, clip: VideoFeed.Clip): Boolean {
        val key = VideoFeed.keyOf(clip)
        return find(ctx, key)?.saved == true
    }

    /** true — теперь в библиотеке. */
    fun toggleSave(ctx: Context, clip: VideoFeed.Clip): Boolean {
        val key = VideoFeed.keyOf(clip)
        val cur = find(ctx, key)
        val now = cur?.saved != true
        upsert(ctx, key) { old ->
            (old ?: Saved(
                key = key,
                title = clip.title,
                author = clip.author,
                source = clip.source,
                url = clip.url,
                thumb = clip.thumb,
                lengthText = clip.lengthText,
                lengthSec = clip.lengthSec,
                added = System.currentTimeMillis()
            )).copy(saved = now, added = if (now) System.currentTimeMillis() else (old?.added ?: clip.title.hashCode().toLong()))
        }
        return now
    }

    /** Завести запись под ролик (чтобы было куда писать позицию просмотра). */
    fun ensure(ctx: Context, clip: VideoFeed.Clip) {
        val key = VideoFeed.keyOf(clip)
        if (find(ctx, key) != null) return
        upsert(ctx, key) {
            Saved(
                key = key,
                title = clip.title,
                author = clip.author,
                source = clip.source,
                url = clip.url,
                thumb = clip.thumb,
                lengthText = clip.lengthText,
                lengthSec = clip.lengthSec,
                added = System.currentTimeMillis(),
                saved = false
            )
        }
    }

    fun remove(ctx: Context, key: String) {
        val cur = list(ctx).toMutableList()
        cur.removeAll { it.key == key }
        saveAll(ctx, cur)
    }

    /** Сохранить позицию просмотра (вызывается плеером каждые несколько секунд). */
    fun setProgress(ctx: Context, key: String, positionMs: Long, durationMs: Long) {
        if (key.isBlank()) return
        val pos = positionMs.coerceAtLeast(0L)
        upsert(ctx, key) { old ->
            val base = old ?: return@upsert null
            if (durationMs > 0 && pos >= durationMs - 1500) base.copy(positionMs = 0L, durationMs = durationMs)
            else base.copy(positionMs = pos, durationMs = if (durationMs > 0) durationMs else base.durationMs)
        }
    }

    fun progressOf(ctx: Context, key: String): Pair<Long, Long> {
        val s = find(ctx, key) ?: return 0L to 0L
        return s.positionMs to s.durationMs
    }

    fun markDownloaded(ctx: Context, clip: VideoFeed.Clip, path: String) {
        val key = VideoFeed.keyOf(clip)
        upsert(ctx, key) { old ->
            (old ?: Saved(
                key = key, title = clip.title, author = clip.author, source = clip.source,
                url = clip.url, thumb = clip.thumb, lengthText = clip.lengthText,
                lengthSec = clip.lengthSec, added = System.currentTimeMillis(), saved = false
            )).copy(localPath = path)
        }
    }

    fun saved(ctx: Context): List<Saved> = list(ctx).filter { it.saved }
    fun withProgress(ctx: Context): List<Saved> = list(ctx).filter { it.positionMs > 3000L && it.localPath.isBlank() }
    fun downloaded(ctx: Context): List<Saved> = list(ctx).filter { it.localPath.isNotBlank() }
}
