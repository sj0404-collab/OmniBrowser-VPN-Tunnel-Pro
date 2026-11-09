package com.winwasm64.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.38.0: автозамена мёртвой раздачи.
 * Проверяем то, что можно проверить без сети: чистку имени, похожесть и правила отбора.
 * Правила жёсткие намеренно — цена ошибки «скачали не то».
 */
class TorrentRescueTest {

    private fun c(name: String, seeders: Int, size: Long = 111L * 1024 * 1024, src: String = "apibay") =
        TorrentRescue.Candidate(name, "magnet:?xt=urn:btih:abc&dn=x", size, seeders, src)

    @Test
    fun `имя раздачи чистится до поискового запроса`() {
        assertEquals("elliot quest", TorrentRescue.cleanQuery("[catorrent.net]-elliot-quest-nsz.torrent"))
        assertEquals("crafty survivors", TorrentRescue.cleanQuery("Crafty Survivors [NSZ].torrent"))
        // служебные слова выкидываются: «The … of» в каталогах только мешают
        assertEquals("legend zelda tears", TorrentRescue.cleanQuery("The Legend of Zelda - Tears (RU).nsp"))
    }

    @Test
    fun `похожесть различает ту же игру и другую`() {
        assertTrue(TorrentRescue.similarity("Elliot Quest [NSZ]", "Elliot Quest") > 0.9)
        assertTrue(TorrentRescue.similarity("Elliot Quest", "Crafty Survivors") < 0.2)
    }

    @Test
    fun `кандидат без сидов не выбирается никогда`() {
        val best = TorrentRescue.pickBest(
            listOf(c("Elliot Quest", seeders = 0), c("Elliot Quest", seeders = 2)),
            "Elliot Quest [NSZ]", 111L * 1024 * 1024
        )
        assertNull(best)
    }

    @Test
    fun `кандидат с другим размером отбрасывается`() {
        val best = TorrentRescue.pickBest(
            listOf(c("Elliot Quest", seeders = 50, size = 7L * 1024 * 1024)),
            "Elliot Quest [NSZ]", 111L * 1024 * 1024
        )
        assertNull(best)
    }

    @Test
    fun `выбирается самый живой из подходящих`() {
        val best = TorrentRescue.pickBest(
            listOf(
                c("Elliot Quest", seeders = 4),
                c("Elliot Quest [NSZ]", seeders = 27),
                c("Elliot Quest OST", seeders = 90, size = 40L * 1024 * 1024)
            ),
            "Elliot Quest [NSZ]", 111L * 1024 * 1024
        )
        assertEquals(27, best?.seeders)
        assertEquals("apibay", best?.source)
    }

    @Test
    fun `размер из RSS nyaa читается`() {
        assertEquals(1536L * 1024 * 1024, TorrentRescue.parseSizeLabel("1.5 GiB"))
        assertEquals(0L, TorrentRescue.parseSizeLabel("unknown"))
    }
}
