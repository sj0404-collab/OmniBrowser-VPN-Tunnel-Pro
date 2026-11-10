package com.winwasm64.ui.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL

/**
 * v4.41.0: обложки раздач.
 *
 * В проекте нет библиотеки загрузки картинок (и не нужно добавлять ещё одну зависимость ради превью),
 * поэтому здесь маленький кеш на 48 картинок: скачали, уменьшили, запомнили. Повторно — из памяти.
 */
object CoverCache {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0 Mobile Safari/537.36"

    private val cache = LruCache<String, Bitmap>(48)

    /**
     * Загрузить обложку. Блокирующий вызов — только из фонового потока.
     *
     * v4.56.0: с [referer]. Часть сайтов (в первую очередь Яндекс Картинки) отдают исходник только
     * при «своём» referer — без него приходили пустые заглушки вместо картинок.
     */
    fun load(url: String, maxSide: Int = 320, referer: String? = null): Bitmap? {
        if (url.isBlank()) return null
        val key = if (referer.isNullOrBlank()) url else "$url|$referer"
        cache.get(key)?.let { return it }
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "image/*")
                if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
            }
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size < 512) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            cache.put(key, bmp)
            bmp
        } catch (_: Throwable) {
            null
        }
    }
}
