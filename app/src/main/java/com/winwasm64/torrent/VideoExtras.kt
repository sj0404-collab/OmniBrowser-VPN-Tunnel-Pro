package com.winwasm64.torrent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v4.88.0: то, что мы добавляли сами, теперь живёт рядом с Rupoop и работает для ВСЕХ площадок:
 * история просмотра, история поиска, плейлисты по типам видео и уведомления о новых роликах каналов.
 */
object VideoHistory {

    private const val PREFS = "omni_video_history"
    private const val KEY_WATCH = "watch"
    private const val KEY_QUERIES = "queries"
    private const val MAX_WATCH = 300
    private const val MAX_QUERIES = 120

    data class Watched(
        val key: String,
        val title: String,
        val author: String,
        val source: String,
        val url: String,
        val thumb: String,
        val lengthText: String,
        val at: Long
    )

    data class Query(val text: String, val source: String, val at: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun addWatch(ctx: Context, c: VideoFeed.Clip) {
        if (c.kind != VideoFeed.CLIP_VIDEO) return
        val key = VideoFeed.keyOf(c)
        val list = watch(ctx).filter { it.key != key }.toMutableList()
        list.add(
            0,
            Watched(
                key = key,
                title = c.title,
                author = c.author,
                source = c.source,
                url = c.url,
                thumb = c.thumb,
                lengthText = c.lengthText,
                at = System.currentTimeMillis()
            )
        )
        saveWatch(ctx, list.take(MAX_WATCH))
    }

    fun watch(ctx: Context, limit: Int = MAX_WATCH): List<Watched> = runCatching {
        val raw = prefs(ctx).getString(KEY_WATCH, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Watched(
                key = o.optString("key"),
                title = o.optString("title"),
                author = o.optString("author"),
                source = o.optString("source"),
                url = o.optString("url"),
                thumb = o.optString("thumb"),
                lengthText = o.optString("length"),
                at = o.optLong("at")
            )
        }.take(limit)
    }.getOrDefault(emptyList())

    fun removeWatch(ctx: Context, key: String) {
        saveWatch(ctx, watch(ctx).filter { it.key != key })
    }

    fun clearWatch(ctx: Context) {
        prefs(ctx).edit().remove(KEY_WATCH).apply()
    }

    private fun saveWatch(ctx: Context, list: List<Watched>) {
        val arr = JSONArray()
        for (w in list) {
            arr.put(
                JSONObject()
                    .put("key", w.key).put("title", w.title).put("author", w.author)
                    .put("source", w.source).put("url", w.url).put("thumb", w.thumb)
                    .put("length", w.lengthText).put("at", w.at)
            )
        }
        prefs(ctx).edit().putString(KEY_WATCH, arr.toString()).apply()
    }

    fun addQuery(ctx: Context, text: String, source: String) {
        val q = text.trim()
        if (q.length < 2) return
        val list = queries(ctx).filter { !(it.text.equals(q, true) && it.source == source) }.toMutableList()
        list.add(0, Query(q, source, System.currentTimeMillis()))
        saveQueries(ctx, list.take(MAX_QUERIES))
    }

    fun queries(ctx: Context, limit: Int = MAX_QUERIES): List<Query> = runCatching {
        val raw = prefs(ctx).getString(KEY_QUERIES, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Query(o.optString("text"), o.optString("source"), o.optLong("at"))
        }.take(limit)
    }.getOrDefault(emptyList())

    fun clearQueries(ctx: Context) {
        prefs(ctx).edit().remove(KEY_QUERIES).apply()
    }

    private fun saveQueries(ctx: Context, list: List<Query>) {
        val arr = JSONArray()
        for (q in list) {
            arr.put(JSONObject().put("text", q.text).put("source", q.source).put("at", q.at))
        }
        prefs(ctx).edit().putString(KEY_QUERIES, arr.toString()).apply()
    }

    fun whenText(at: Long): String =
        SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(at))
}

/**
 * v4.88.0: плейлисты с названием и типом видео. Тип берём из самой карточки,
 * чтобы «музыка», «видео» и «трансляции» не сваливались в один список.
 */
object VideoPlaylists {

    private const val PREFS = "omni_video_playlists"

    data class Pl(val name: String, val kind: String, val count: Int)

    /** Тип ролика для группировки: видео / музыка / трансляция / фильм. */
    fun kindOf(c: VideoFeed.Clip): String = when {
        c.live -> "трансляции"
        c.lengthSec in 1..600 -> "короткие"
        c.lengthSec > 3600 -> "фильмы и сериалы"
        else -> "видео"
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun create(ctx: Context, name: String, kind: String): Boolean {
        val n = name.trim()
        if (n.isBlank()) return false
        if (list(ctx).any { it.name.equals(n, true) }) return false
        val root = root(ctx)
        root.put(n, JSONObject().put("kind", kind).put("items", JSONArray()))
        prefs(ctx).edit().putString("data", root.toString()).apply()
        return true
    }

    fun list(ctx: Context): List<Pl> {
        val root = root(ctx)
        return root.keys().asSequence().mapNotNull { name ->
            val o = root.optJSONObject(name) ?: return@mapNotNull null
            Pl(name, o.optString("kind", "видео"), o.optJSONArray("items")?.length() ?: 0)
        }.sortedBy { it.name }.toList()
    }

    fun add(ctx: Context, name: String, c: VideoFeed.Clip): Boolean {
        val root = root(ctx)
        val o = root.optJSONObject(name) ?: return false
        val items = o.optJSONArray("items") ?: JSONArray()
        val key = VideoFeed.keyOf(c)
        for (i in 0 until items.length()) {
            if (items.optJSONObject(i)?.optString("key") == key) return true
        }
        items.put(
            JSONObject()
                .put("key", key).put("title", c.title).put("author", c.author)
                .put("source", c.source).put("url", c.url).put("thumb", c.thumb)
                .put("length", c.lengthText).put("sec", c.lengthSec).put("live", c.live)
        )
        o.put("items", items)
        root.put(name, o)
        prefs(ctx).edit().putString("data", root.toString()).apply()
        return true
    }

    fun items(ctx: Context, name: String): List<VideoFeed.Clip> {
        val o = root(ctx).optJSONObject(name) ?: return emptyList()
        val arr = o.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val j = arr.optJSONObject(i) ?: return@mapNotNull null
            VideoFeed.Clip(
                id = j.optString("key"),
                kind = VideoFeed.CLIP_VIDEO,
                title = j.optString("title"),
                author = j.optString("author"),
                lengthText = j.optString("length"),
                viewsText = "",
                publishedText = "",
                thumb = j.optString("thumb"),
                url = j.optString("url"),
                live = j.optBoolean("live"),
                source = j.optString("source"),
                lengthSec = j.optInt("sec")
            )
        }
    }

    fun removeItem(ctx: Context, name: String, key: String) {
        val root = root(ctx)
        val o = root.optJSONObject(name) ?: return
        val items = o.optJSONArray("items") ?: return
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val j = items.optJSONObject(i) ?: continue
            if (j.optString("key") != key) out.put(j)
        }
        o.put("items", out)
        root.put(name, o)
        prefs(ctx).edit().putString("data", root.toString()).apply()
    }

    fun remove(ctx: Context, name: String) {
        val root = root(ctx)
        root.remove(name)
        prefs(ctx).edit().putString("data", root.toString()).apply()
    }

    private fun root(ctx: Context): JSONObject = runCatching {
        JSONObject(prefs(ctx).getString("data", "{}") ?: "{}")
    }.getOrDefault(JSONObject())
}

/**
 * v4.88.0: уведомления о новых роликах каналов, на которые человек подписан.
 * Проверяем только свои подписки (YouTube/RuTube/VK), ничего не отправляем наружу.
 */
object VideoNotify {

    private const val PREFS = "omni_video_notify"
    private const val CHANNEL_ID = "omni_video_updates"

    data class Fresh(val channel: String, val source: String, val title: String, val url: String)

    /** Проверить подписки: возвращает ролики, которых раньше не видели. */
    fun check(ctx: Context, maxSubs: Int = 8): List<Fresh> {
        val subs = VideoSubs.list(ctx)
        if (subs.isEmpty()) return emptyList()
        val seen = seenSet(ctx)
        val out = ArrayList<Fresh>()
        for (ch in subs.take(maxSubs)) {
            val page = runCatching {
                VideoFeed.channelPage(ch.source, ch.name, ch.url)
            }.getOrNull() ?: continue
            val first = page.clips.firstOrNull { it.kind == VideoFeed.CLIP_VIDEO } ?: continue
            val key = VideoFeed.keyOf(first)
            if (seen.add(key)) out.add(Fresh(ch.name, ch.source, first.title, first.url))
        }
        if (out.isNotEmpty()) saveSeen(ctx, seen)
        if (out.isNotEmpty()) notify(ctx, out)
        return out
    }

    private fun seenSet(ctx: Context): MutableSet<String> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("seen", null)
        return (raw ?: emptySet()).toMutableSet()
    }

    private fun saveSeen(ctx: Context, set: Set<String>) {
        // держим не больше 600 ключей, чтобы не разрасталось
        val trimmed = set.toList().takeLast(600).toSet()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("seen", trimmed).apply()
    }

    fun unseenCount(ctx: Context): Int = seenSet(ctx).size.let { 0 }  // для совместимости

    private fun notify(ctx: Context, fresh: List<Fresh>) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Новые видео каналов", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            val flags = if (Build.VERSION.SDK_INT >= 23)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = if (launch != null) PendingIntent.getActivity(ctx, 8801, launch, flags) else null
            val body = fresh.take(3).joinToString("\n") { "${it.channel}: ${it.title.take(48)}" }
            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Новое у каналов: ${fresh.size}")
                .setContentText(body.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
            if (pi != null) builder.setContentIntent(pi)
            nm.notify(8801, builder.build())
        }
    }
}
