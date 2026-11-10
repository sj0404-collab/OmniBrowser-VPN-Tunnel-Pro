package com.winwasm64.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4.43.0: выбор качества и формата — «хочу 360p», «только FLAC». */
class QualityPickerTest {

    private fun f(name: String) = TorrentSearchAgent.Found(name = name)

    @Test
    fun `разрешение читается из названия`() {
        assertEquals("720p", TorrentSearchAgent.qualityOf("Шерлок Холмс (2009) WEB-DL 720p x264"))
        assertEquals("360p", TorrentSearchAgent.qualityOf("Шерлок Холмс 360p"))
        assertEquals("2160p", TorrentSearchAgent.qualityOf("Дюна 2 4K HDR"))
        assertEquals("1080p", TorrentSearchAgent.qualityOf("Фильм Full HD"))
        assertEquals("", TorrentSearchAgent.qualityOf("Шерлок Холмс аудиокнига"))
    }

    @Test
    fun `кодек и звук распознаются`() {
        assertEquals("x265 (HEVC)", TorrentSearchAgent.codecOf("Фильм 1080p HEVC"))
        assertEquals("x264", TorrentSearchAgent.codecOf("Фильм 720p x264"))
        assertEquals("FLAC", TorrentSearchAgent.audioOf("Pink Floyd Discography FLAC"))
        assertEquals("MP3", TorrentSearchAgent.audioOf("Сборник mp3 320"))
    }

    @Test
    fun `запрос с числом превращается в качество`() {
        assertEquals("360p", TorrentSearchAgent.parseIntent("шерлок 360").quality)
        assertEquals("1080p", TorrentSearchAgent.parseIntent("дюна 1080p").quality)
        assertEquals("flac", TorrentSearchAgent.parseIntent("pink floyd flac").audio)
    }

    @Test
    fun `точное качество находится, иначе предлагается ближайшее`() {
        val items = listOf(
            f("Фильм 1080p"), f("Фильм 720p"), f("Фильм 480p")
        ).onEach { it.quality = TorrentSearchAgent.qualityOf(it.name) }

        val exact = TorrentSearchAgent.filterByQuality(items, "720p")
        assertEquals(1, exact.size)
        assertEquals("720p", exact.first().quality)

        val nearest = TorrentSearchAgent.filterByQuality(items, "360p")
        assertTrue(nearest.isNotEmpty())
        assertEquals("480p", nearest.first().quality) // ближайшее к 360p
    }

    @Test
    fun `список доступных качеств отсортирован от лучшего`() {
        val items = listOf(f("A 480p"), f("B 1080p"), f("C 720p"))
            .onEach { it.quality = TorrentSearchAgent.qualityOf(it.name) }
        assertEquals(listOf("1080p", "720p", "480p"), TorrentSearchAgent.availableQualities(items))
    }
}

/** v4.44.0: категории и запрос к сайтам. */
class CategoryAndQueryTest {

    @Test
    fun `фильм с словом Game в названии остаётся видео`() {
        assertEquals(
            "video",
            TorrentSearchAgent.classify("Шерлок Холмс: Игра теней (2011) AC3 5.1 DVDRiP")
        )
        assertEquals("game", TorrentSearchAgent.classify("Cyberpunk 2077 Repack", platform = "PC"))
    }

    @Test
    fun `качество не попадает в запрос к трекерам`() {
        assertEquals("шерлок", TorrentSearchAgent.stripQueryNoise("шерлок 360p"))
        assertEquals("дюна", TorrentSearchAgent.stripQueryNoise("дюна 1080p x265"))
        assertEquals("pink floyd", TorrentSearchAgent.stripQueryNoise("pink floyd flac"))
    }
}

/** v4.45.0: ссылки поисковиков разворачиваются в настоящие адреса трекеров. */
class SearchRedirectTest {

    @Test
    fun `ссылка DuckDuckGo разворачивается`() {
        assertEquals(
            "https://rutor.info/torrent/12345/sherlock",
            TorrentSearchAgent.resolveSearchRedirect(
                "https://duckduckgo.com/l/?uddg=https%3A%2F%2Frutor.info%2Ftorrent%2F12345%2Fsherlock&rut=abc"
            )
        )
    }

    @Test
    fun `ссылка Google urlq разворачивается`() {
        assertEquals(
            "https://nnmclub.to/forum/viewtopic.php?t=999",
            TorrentSearchAgent.resolveSearchRedirect(
                "https://www.google.com/url?q=https%3A%2F%2Fnnmclub.to%2Fforum%2Fviewtopic.php%3Ft%3D999&sa=U"
            )
        )
    }

    @Test
    fun `обычная ссылка не меняется`() {
        assertEquals(
            "https://byxatab.com/igry/12345-sherlock.html",
            TorrentSearchAgent.resolveSearchRedirect("https://byxatab.com/igry/12345-sherlock.html")
        )
    }
}

/** v4.46.0: по умолчанию открываем видео/аудио, а не .iso/.img/.txt из той же раздачи. */
class MediaSelectionTest {

    @Test
    fun `образы и тексты не считаются медиа`() {
        assertFalse(TorrentStreamer.isMedia("Holmes.img"))
        assertFalse(TorrentStreamer.isMedia("LIVANOV1.ISO"))
        assertFalse(TorrentStreamer.isMedia("6"))
        assertFalse(TorrentStreamer.isMedia("readme.txt"))
    }

    @Test
    fun `видео и музыка считаются медиа`() {
        assertTrue(TorrentStreamer.isMedia("film.mkv"))
        assertTrue(TorrentStreamer.isMedia("serial.S01E01.mp4"))
        assertTrue(TorrentStreamer.isMedia("track.FLAC"))
        assertTrue(TorrentStreamer.isMedia("voice.m4a"))
    }
}
