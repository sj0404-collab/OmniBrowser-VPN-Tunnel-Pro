package com.winwasm64.ui

import android.content.Context
import org.json.JSONObject

/**
 * v4.8.0: запомненные позиции воспроизведения (ключ → секунды). Исправляет «видео с YouTube начинается заново
 * при каждом открытии страницы»: мобильный YouTube без входа в аккаунт не помнит место, а при восстановлении вкладок
 * страница загружается заново. Храним до 400 записей, 60 дней.
 */
object MediaPositions {
    private const val PREFS = "omni_media_pos"
    private const val MAX = 400
    private const val TTL_MS = 60L * 24 * 3600_000
    private var ctx: Context? = null
    private val map = LinkedHashMap<String, Pair<Double, Long>>()
    private var loaded = false
    private var lastPersist = 0L

    /** v4.9.0: автозапуск следующего видео (ключ auto_next_video в настройках браузера, по умолчанию включён). */
    @Volatile var autoNext: Boolean = true
    /** v4.10.3: WebRTC только через прокси (TCP) на всех сайтах (для GeForce NOW/NVIDIA — всегда). */
    @Volatile var webrtcTcpAll: Boolean = false

    fun init(context: Context) {
        ctx = context.applicationContext
        autoNext = context.getSharedPreferences("omni_browser_v21_prefs", Context.MODE_PRIVATE).getBoolean("auto_next_video", true)
        webrtcTcpAll = context.getSharedPreferences("omni_browser_v21_prefs", Context.MODE_PRIVATE).getBoolean("webrtc_tcp_all", false)
        synchronized(map) {
            if (loaded) return
            loaded = true
            try {
                val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", null) ?: return
                val o = JSONObject(raw)
                val now = System.currentTimeMillis()
                for (k in o.keys()) {
                    val e = o.getJSONObject(k)
                    val ts = e.optLong("ts")
                    if (now - ts < TTL_MS) map[k] = e.optDouble("t") to ts
                }
            } catch (_: Exception) {}
        }
    }

    fun save(key: String, seconds: Double, duration: Double) {
        if (key.isBlank() || seconds.isNaN()) return
        synchronized(map) {
            // досмотрено до конца — забываем, чтобы в следующий раз начать сначала
            if (duration > 0 && seconds > duration - 15) map.remove(key) else { map.remove(key); map[key] = seconds to System.currentTimeMillis() }
            while (map.size > MAX) map.remove(map.keys.first())
            val now = System.currentTimeMillis()
            if (now - lastPersist > 10_000) { lastPersist = now; persist() }
        }
    }

    fun get(key: String): Double = synchronized(map) { map[key]?.first ?: 0.0 }

    fun flush() = synchronized(map) { persist() }

    private fun persist() {
        val c = ctx ?: return
        val o = JSONObject()
        for ((k, v) in map) o.put(k, JSONObject().put("t", v.first).put("ts", v.second))
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("json", o.toString()).apply()
    }
}
