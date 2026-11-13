package com.santiago43rus.rupoop.network

import com.santiago43rus.rupoop.data.SearchResult
import com.santiago43rus.rupoop.parser.OkVideoParser
import com.santiago43rus.rupoop.parser.UniversalVideoParser
import com.santiago43rus.rupoop.parser.VkVideoParser

/**
 * Strategy interface for searching videos on a specific platform.
 * Adheres to SOLID: Single Responsibility, Open-Closed, and Dependency Inversion Principles.
 */
interface PlatformVideoSearchEngine {
    suspend fun search(query: String): List<SearchResult>
}

object RutubePlatformSearchEngine : PlatformVideoSearchEngine {
    override suspend fun search(query: String): List<SearchResult> {
        return try {
            RetrofitClient.api.searchVideos(query).results
        } catch (_: Exception) {
            emptyList()
        }
    }
}

object VkPlatformSearchEngine : PlatformVideoSearchEngine {
    override suspend fun search(query: String): List<SearchResult> {
        return try {
            VkSearchEngine.search(query)
        } catch (_: Exception) {
            emptyList()
        }
    }
}

object OkPlatformSearchEngine : PlatformVideoSearchEngine {
    override suspend fun search(query: String): List<SearchResult> {
        return try {
            OkSearchEngine.search(query)
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * v4.89.0: YouTube как обычная площадка Rupoop.
 * Сам поиск делает приложение (через свой канал сети), сюда приходят уже готовые карточки.
 */
object YouTubePlatformSearchEngine : PlatformVideoSearchEngine {
    override suspend fun search(query: String): List<SearchResult> {
        return try {
            OmniYouTubeBridge.search(query)
        } catch (_: Exception) {
            emptyList()
        }
    }
}

object LordfilmPlatformSearchEngine : PlatformVideoSearchEngine {
    override suspend fun search(query: String): List<SearchResult> {
        return try {
            LordfilmSearchEngine.search(query)
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * Resolver for obtaining the appropriate platform engine for a video URL.
 */
object PlatformSearchEngineResolver {

    /** Ссылка на YouTube (watch, shorts, live, youtu.be, embed). */
    fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com/") || lower.contains("youtu.be/") ||
            lower.contains("m.youtube.com/") || lower.contains("youtube-nocookie.com/")
    }

    fun isLordfilmUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("lordfilm") || lower.contains("lordserials")
    }

    fun resolve(videoUrl: String): PlatformVideoSearchEngine {
        return when {
            isYouTubeUrl(videoUrl) -> YouTubePlatformSearchEngine
            UniversalVideoParser.isRutubeUrl(videoUrl) -> RutubePlatformSearchEngine
            VkVideoParser.isVkUrl(videoUrl) -> VkPlatformSearchEngine
            OkVideoParser.isOkUrl(videoUrl) -> OkPlatformSearchEngine
            isLordfilmUrl(videoUrl) -> LordfilmPlatformSearchEngine
            else -> RutubePlatformSearchEngine
        }
    }

    fun getPlatformDisplayName(videoUrl: String): String {
        return when {
            isYouTubeUrl(videoUrl) -> "YouTube"
            UniversalVideoParser.isRutubeUrl(videoUrl) -> "Rutube"
            VkVideoParser.isVkUrl(videoUrl) -> "ВКонтакте"
            OkVideoParser.isOkUrl(videoUrl) -> "Одноклассники"
            isLordfilmUrl(videoUrl) -> "Lordfilm"
            else -> "Видео"
        }
    }
}
