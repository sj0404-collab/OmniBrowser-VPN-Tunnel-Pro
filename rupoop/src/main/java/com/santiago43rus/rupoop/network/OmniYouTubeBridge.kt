package com.santiago43rus.rupoop.network

import android.util.Log
import com.santiago43rus.rupoop.data.SearchResult

/**
 * v4.89.0: мост «Кино (Rupoop) → YouTube».
 *
 * Модуль `:rupoop` не должен знать, как именно приложение ходит в сеть (канал VPN браузера,
 * запасные клиенты innertube, Piped/Invidious). Поэтому конкретные реализации подставляет
 * app-модуль при старте приложения, а Rupoop пользуется ими так же, как своими движками
 * RuTube/VK/OK/Lordfilm: YouTube становится ещё одной площадкой прямо в его поиске,
 * ленте, подписках и встроенном плеере.
 */
object OmniYouTubeBridge {

    private const val TAG = "OmniYouTubeBridge"

    /** Играбельный поток, найденный приложением. */
    data class Stream(
        val streamUrl: String,
        val headers: Map<String, String> = emptyMap(),
        val title: String? = null
    )

    @Volatile
    var installed: Boolean = false
        private set

    @Volatile
    private var searchFn: (suspend (String) -> List<SearchResult>)? = null

    @Volatile
    private var channelFn: (suspend (String) -> List<SearchResult>)? = null

    @Volatile
    private var streamFn: ((String) -> Stream?)? = null

    fun install(
        search: suspend (String) -> List<SearchResult>,
        channel: suspend (String) -> List<SearchResult>,
        stream: (String) -> Stream?
    ) {
        searchFn = search
        channelFn = channel
        streamFn = stream
        installed = true
    }

    /** Поиск по YouTube. Пустой список, если приложение не подключило YouTube или сеть не ответила. */
    suspend fun search(query: String): List<SearchResult> {
        val fn = searchFn ?: return emptyList()
        return try {
            fn(query)
        } catch (t: Throwable) {
            Log.w(TAG, "YouTube search failed: ${t.message}")
            emptyList()
        }
    }

    /** Видео конкретного канала (по ссылке канала, иначе по названию). */
    suspend fun channel(channelRef: String): List<SearchResult> {
        val fn = channelFn ?: return emptyList()
        if (channelRef.isBlank()) return emptyList()
        return try {
            fn(channelRef)
        } catch (t: Throwable) {
            Log.w(TAG, "YouTube channel failed: ${t.message}")
            emptyList()
        }
    }

    /** Прямой поток для ссылки YouTube (watch/shorts/youtu.be/live). */
    fun stream(url: String): Stream? {
        val fn = streamFn ?: return null
        return try {
            fn(url)
        } catch (t: Throwable) {
            Log.w(TAG, "YouTube stream failed: ${t.message}")
            null
        }
    }
}
