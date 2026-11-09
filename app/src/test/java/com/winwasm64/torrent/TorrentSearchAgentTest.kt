package com.winwasm64.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.39.0: поисковый агент.
 * Проверяем разбор запроса, ранжирование, объяснение выбора и правила «что можно запускать само».
 */
class TorrentSearchAgentTest {

    private fun f(
        name: String,
        seeders: Int = 0,
        size: Long = 111L * 1024 * 1024,
        source: String = "apibay",
        platform: String = "",
        magnet: String = "magnet:?xt=urn:btih:abc",
        torrentUrl: String = ""
    ) = TorrentSearchAgent.Found(
        name = name, magnet = magnet, torrentUrl = torrentUrl, sizeBytes = size,
        seeders = seeders, source = source, platform = platform
    )

    @Test
    fun `запрос разбирается на название, формат и платформу`() {
        val i = TorrentSearchAgent.parseIntent("elliot quest switch nsz")
        assertEquals("elliot quest", i.title)
        assertEquals("nsz", i.format)
        assertEquals("Nintendo Switch", i.platform)
    }

    @Test
    fun `apk и видео тоже понимаются`() {
        assertEquals("apk", TorrentSearchAgent.parseIntent("telegram apk").format)
        assertEquals("mkv", TorrentSearchAgent.parseIntent("фильм дюна mkv").format)
        assertEquals("Android", TorrentSearchAgent.parseIntent("игра android").platform)
    }

    @Test
    fun `ранжирование ставит живое и похожее выше`() {
        val intent = TorrentSearchAgent.parseIntent("elliot quest switch")
        val ranked = TorrentSearchAgent.rank(
            listOf(
                f("Elliot Quest (SWITCH)", seeders = 18, platform = "Nintendo Switch"),
                f("Elliot Quest OST", seeders = 40, size = 40L * 1024 * 1024),
                f("Crafty Survivors", seeders = 99)
            ),
            intent, "Elliot Quest [NSZ]", 111L * 1024 * 1024
        )
        assertTrue(ranked.first().name.contains("Elliot Quest (SWITCH)"))
        assertTrue(ranked.first().reason.contains("18 сидов"))
    }

    @Test
    fun `catorrent без сидов всё равно запускается при точном имени и размере`() {
        val intent = TorrentSearchAgent.parseIntent("elliot quest switch")
        val found = listOf(
            f("Elliot Quest (SWITCH) скачать торрент", seeders = 0, source = "catorrent",
                magnet = "", torrentUrl = "https://catorrent.net/index.php?do=download&id=28603")
        )
        val ranked = TorrentSearchAgent.rank(found, intent, "Elliot Quest", 111L * 1024 * 1024)
        assertTrue(TorrentSearchAgent.bestForAutoStart(ranked, intent, "Elliot Quest", 111L * 1024 * 1024) != null)
    }

    @Test
    fun `без сидов и без точного совпадения сам не запускает`() {
        val intent = TorrentSearchAgent.parseIntent("elliot quest")
        val found = listOf(f("Elliot Quest", seeders = 1, size = 7L * 1024 * 1024))
        val ranked = TorrentSearchAgent.rank(found, intent, "Elliot Quest", 111L * 1024 * 1024)
        assertNull(TorrentSearchAgent.bestForAutoStart(ranked, intent, "Elliot Quest", 111L * 1024 * 1024))
    }

    @Test
    fun `аналоги предлагаются, когда точной замены нет`() {
        val ranked = listOf(
            f("Elliot Quest Repack", seeders = 12),
            f("Elliot Quest Soundtrack", seeders = 3),
            f("Совсем другое", seeders = 100)
        )
        val analogs = TorrentSearchAgent.analogs(ranked, "Elliot Quest [NSZ]")
        assertTrue(analogs.isNotEmpty())
        assertTrue(analogs.none { it.name == "Совсем другое" })
    }
}
