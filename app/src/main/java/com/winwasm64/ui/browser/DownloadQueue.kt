package com.winwasm64.ui.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * v4.5.0: очередь загрузок с паузой/продолжением и сохранением между перезапусками.
 *
 * Состояние каждой загрузки хранится в prefs `omni_dl_queue`; скачанные части — в `filesDir/dl/<id>.pN`
 * (докачка по Range с того места, где остановились). После успешного завершения запись и части удаляются.
 */
object DownloadQueue {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val ERROR = "error"

    class Entry(
        val id: String,
        val job: MediaGrabber.DownloadJob,
        @Volatile var state: String = QUEUED,
        @Volatile var done: Long = 0L,
        @Volatile var total: Long = 0L,
        @Volatile var speed: Long = 0L,
        @Volatile var stage: String = "",
        @Volatile var error: String = "",
        val addedAt: Long = System.currentTimeMillis()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("job", job.toJson()); put("state", state); put("done", done); put("total", total)
            put("error", error); put("addedAt", addedAt)
        }

        companion object {
            fun fromJson(o: JSONObject): Entry? {
                val job = MediaGrabber.DownloadJob.fromJson(o.optString("job")) ?: return null
                return Entry(
                    id = o.optString("id"), job = job, state = o.optString("state", QUEUED),
                    done = o.optLong("done"), total = o.optLong("total"), error = o.optString("error"), addedAt = o.optLong("addedAt")
                )
            }
        }
    }

    private const val PREFS = "omni_dl_queue"
    private val entries = ConcurrentHashMap<String, Entry>()
    /** Счётчик изменений для Compose: UI читает [snapshot] заново при каждом изменении. */
    val version = mutableIntStateOf(0)
    @Volatile private var loaded = false
    private val main = Handler(Looper.getMainLooper())

    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("entries", null) ?: return
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val e = Entry.fromJson(arr.getJSONObject(i)) ?: continue
                    // сервис умер посреди загрузки — ставим в очередь, части на диске подхватятся
                    if (e.state == RUNNING) e.state = QUEUED
                    e.speed = 0L
                    entries[e.id] = e
                }
            } catch (_: Exception) {}
        }
        bump()
    }

    fun snapshot(): List<Entry> = entries.values.sortedBy { it.addedAt }
    fun get(id: String): Entry? = entries[id]
    fun hasPending(): Boolean = entries.values.any { it.state == QUEUED }
    fun queued(): List<Entry> = snapshot().filter { it.state == QUEUED }
    fun activeCount(): Int = entries.values.count { it.state == QUEUED || it.state == RUNNING }

    fun add(ctx: Context, job: MediaGrabber.DownloadJob): Entry {
        load(ctx)
        val id = java.lang.Long.toHexString(System.currentTimeMillis()) + "-" + (job.fileName.hashCode().toUInt().toString(16))
        val e = Entry(id, job)
        entries[id] = e
        save(ctx)
        return e
    }

    fun setState(ctx: Context, id: String, state: String, error: String = "") {
        val e = entries[id] ?: return
        e.state = state
        e.error = error
        if (state != RUNNING) e.speed = 0L
        save(ctx)
    }

    fun progress(id: String, done: Long, total: Long, speed: Long, stage: String) {
        val e = entries[id] ?: return
        e.done = done; if (total > 0) e.total = total; e.speed = speed; e.stage = stage
        bump()
    }

    fun remove(ctx: Context, id: String) {
        val e = entries.remove(id) ?: return
        deleteParts(ctx, e.id)
        save(ctx)
    }

    fun partsDir(ctx: Context): File = File(ctx.filesDir, "dl").apply { mkdirs() }
    fun partFile(ctx: Context, id: String, index: Int): File = File(partsDir(ctx), "$id.p$index")
    fun metaFile(ctx: Context, id: String, name: String): File = File(partsDir(ctx), "$id.$name")
    fun partsBytes(ctx: Context, id: String): Long = partsDir(ctx).listFiles()?.filter { it.name.startsWith("$id.p") }?.sumOf { it.length() } ?: 0L

    fun deleteParts(ctx: Context, id: String) {
        try { partsDir(ctx).listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.delete() } } catch (_: Exception) {}
    }

    /** Удаляет осиротевшие части (записей о которых уже нет). */
    fun sweep(ctx: Context) {
        try {
            partsDir(ctx).listFiles()?.forEach { f ->
                val id = f.name.substringBefore('.')
                if (!entries.containsKey(id)) f.delete()
            }
        } catch (_: Exception) {}
    }

    private fun save(ctx: Context) {
        try {
            val arr = JSONArray()
            for (e in snapshot()) arr.put(e.toJson())
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("entries", arr.toString()).apply()
        } catch (_: Exception) {}
        bump()
    }

    private fun bump() {
        if (Looper.myLooper() == Looper.getMainLooper()) version.intValue++ else main.post { version.intValue++ }
    }

    fun fmt(e: Entry): String = buildString {
        val pct = if (e.total > 0) (e.done * 100 / e.total).toInt().coerceIn(0, 100) else -1
        when (e.state) {
            QUEUED -> append("в очереди")
            RUNNING -> append("качается")
            PAUSED -> append("на паузе")
            ERROR -> append("ошибка")
        }
        if (pct >= 0) append(" · $pct%")
        if (e.done > 0) append(" · ").append(MediaGrabber.fmtSize(e.done))
        if (e.total > 0) append(" из ").append(MediaGrabber.fmtSize(e.total))
        if (e.state == RUNNING && e.speed > 0) append(" · ").append(MediaGrabber.fmtSize(e.speed)).append("/с")
        if (e.state == RUNNING && e.stage.isNotBlank()) append(" · ").append(e.stage)
        if (e.state == ERROR && e.error.isNotBlank()) append(": ").append(e.error.take(120))
    }
}
