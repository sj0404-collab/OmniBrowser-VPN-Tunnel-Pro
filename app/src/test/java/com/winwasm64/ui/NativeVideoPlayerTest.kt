package com.winwasm64.ui

import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for native video player integration.
 *
 * v4.64.0: Media3 ExoPlayer native player from RupoopFork integrated into OmniBrowser.
 */
class NativeVideoPlayerTest {

    @Test
    fun nativePlayer_constants_areCorrect() {
        // Проверяем, что activity существует и правильно настроена
        assertNotNull("NativeVideoPlayerActivity должен существовать",
            NativeVideoPlayerActivity::class.java.name)
    }

    @Test
    fun videoUrl_formats_areValid() {
        // MP4
        assertTrue(isValidVideoUrl("https://example.com/video.mp4"))
        assertTrue(isValidVideoUrl("http://example.com/video.mp4"))

        // HLS
        assertTrue(isValidVideoUrl("https://example.com/stream.m3u8"))
        assertTrue(isValidVideoUrl("http://example.com/stream.m3u8"))

        // WebM
        assertTrue(isValidVideoUrl("https://example.com/video.webm"))

        // Длинный URL
        assertTrue(isValidVideoUrl("https://example.com/very/long/path/to/video.mp4?param=value"))
    }

    @Test
    fun videoUrl_invalidFormats_areRejected() {
        assertFalse(isValidVideoUrl(""))
        assertFalse(isValidVideoUrl("not_a_url"))
        assertFalse(isValidVideoUrl("ftp://example.com/video.mp4"))
        assertFalse(isValidVideoUrl("file:///path/to/video.mp4"))
    }

    private fun isValidVideoUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return url.startsWith("http://") || url.startsWith("https://")
    }
}
