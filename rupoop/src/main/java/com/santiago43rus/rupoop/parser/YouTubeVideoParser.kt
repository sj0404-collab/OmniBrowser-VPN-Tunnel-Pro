package com.santiago43rus.rupoop.parser

import android.util.Log
import com.santiago43rus.rupoop.network.OmniYouTubeBridge

/**
 * v4.89.0: разбор ссылки YouTube для встроенного плеера.
 *
 * Раньше ссылка на YouTube открывалась сайтом/внешним приложением. Теперь приложение само
 * достаёт прямой поток (progressive mp4 или HLS) через свой канал сети, а этот разбор
 * отдаёт его ExoPlayer — видео играет внутри Rupoop, как RuTube и VK.
 */
object YouTubeVideoParser {

    private const val TAG = "YouTubeVideoParser"

    fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com/") || lower.contains("youtu.be/") ||
            lower.contains("m.youtube.com/") || lower.contains("youtube-nocookie.com/")
    }

    fun extractId(url: String): String? {
        val u = url.trim()
        val patterns = listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""/shorts/([A-Za-z0-9_-]{11})"""),
            Regex("""/embed/([A-Za-z0-9_-]{11})"""),
            Regex("""/live/([A-Za-z0-9_-]{11})""")
        )
        for (p in patterns) p.find(u)?.let { return it.groupValues[1] }
        if (Regex("""^[A-Za-z0-9_-]{11}$""").matches(u)) return u
        return null
    }

    suspend fun parse(url: String): ParsedVideo? {
        val id = extractId(url)
        if (id == null) {
            Log.w(TAG, "no video id in $url")
            return null
        }
        // 1) свой разбор (как у RuTube/VK) — работает без приложения
        val own = YouTubeStreamResolver.resolve("https://www.youtube.com/watch?v=$id")
        if (own != null && own.streamUrl.isNotBlank()) {
            return ParsedVideo(
                videoUrl = url,
                streamUrl = own.streamUrl,
                title = own.title?.takeIf { it.isNotBlank() } ?: "YouTube",
                thumbnailUrl = own.thumbnailUrl ?: "https://i.ytimg.com/vi/$id/mqdefault.jpg",
                authorName = "YouTube",
                durationSeconds = own.durationSeconds,
                sourceName = "YouTube",
                headers = own.headers
            )
        }
        // 2) мост приложения (канал/VPN и запасные пути)
        val stream = OmniYouTubeBridge.stream("https://www.youtube.com/watch?v=$id")
        if (stream == null || stream.streamUrl.isBlank()) {
            Log.w(TAG, "YouTube stream not resolved for $id")
            return null
        }
        return ParsedVideo(
            videoUrl = url,
            streamUrl = stream.streamUrl,
            title = stream.title?.takeIf { it.isNotBlank() } ?: "YouTube",
            thumbnailUrl = "https://i.ytimg.com/vi/$id/mqdefault.jpg",
            authorName = "YouTube",
            sourceName = "YouTube",
            headers = stream.headers
        )
    }
}
