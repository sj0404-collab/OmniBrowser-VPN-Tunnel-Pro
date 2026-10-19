package com.winwasm64

import com.winwasm64.engine.Wasm64WindowsRuntime
import com.winwasm64.torrent.BitTorrentEngine
import com.winwasm64.torrent.BitTorrentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WindowsWasm64RuntimeTest {

    private fun createTempRuntime(): Wasm64WindowsRuntime {
        val tmpDir = Files.createTempDirectory("win_wasm64_test").toFile()
        return Wasm64WindowsRuntime(tmpDir)
    }

    @Test
    fun testBitTorrentBencodeAndMetadataParser() {
        val samplePayload = ByteArray(40_000) { (it % 251).toByte() }
        val torrentBytes = BitTorrentParser.createSyntheticTorrentBytes(
            name = "doom_shareware_v19.exe",
            payload = samplePayload,
            pieceLength = 16384,
            announceUrl = "http://tracker.opentrackr.org:1337/announce",
            webSeedUrl = "https://archive.org/download/DoomsharewareEpisode/Doom19s.zip"
        )

        val meta = BitTorrentParser.parseTorrentBytes(torrentBytes)
        assertEquals("doom_shareware_v19.exe", meta.name)
        assertEquals(40_000L, meta.totalSize)
        assertEquals(16384, meta.pieceLength)
        assertEquals(3, meta.pieceCount)
        assertEquals(40, meta.infoHashHex.length)
        assertTrue(meta.trackers.contains("http://tracker.opentrackr.org:1337/announce"))
        assertTrue(meta.webSeeds.contains("https://archive.org/download/DoomsharewareEpisode/Doom19s.zip"))

        val firstPieceSha1 = BitTorrentParser.sha1(samplePayload.copyOfRange(0, 16384))
        assertTrue(firstPieceSha1.contentEquals(meta.pieceHashes[0]))
    }

    @Test
    fun testMagnetLinkHexAndBase32Parsing() {
        val magnetHex = "magnet:?xt=urn:btih:6a9759bffd5c0af65319979fb7832189f4f3c35d&dn=DOOM_v1.9.zip&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce&ws=https%3A%2F%2Farchive.org%2Fdownload%2FDoom19s.zip"
        val parsed = BitTorrentParser.parseMagnetUri(magnetHex)
        assertEquals("6a9759bffd5c0af65319979fb7832189f4f3c35d", parsed.infoHashHex)
        assertEquals("DOOM_v1.9.zip", parsed.displayName)
        assertTrue(parsed.trackers.any { it.contains("opentrackr.org") })
        assertTrue(parsed.webSeeds.any { it.contains("archive.org") })

        val engine = BitTorrentEngine(null)
        val catalog = engine.getBuiltInVerifiedTorrents()
        assertTrue(catalog.size >= 5)
        assertTrue(catalog.any { it.fileName.endsWith(".exe") || it.fileName.endsWith(".zip") })
        assertTrue(catalog.any { it.fileName.endsWith(".mp4") })
    }

    @Test
    fun testWasm64ModuleCompilationAndMemory64Execution() {
        val rt = createTempRuntime()
        val wasmBytes = rt.assembleWasm64AddMulModule(a = 1000L, b = 2000L, c = 64L)
        val res = rt.executeWasm64Module(wasmBytes)

        assertTrue(res.validMagicHeader)
        assertTrue(res.memory64Addressing)
        assertEquals(192000L, res.returnValueI64)
        assertTrue(res.executedInstructions >= 7L)
    }

    @Test
    fun testYouTubeAndBrowserExtensionsAdBlockAndBrowsec() {
        val rt = createTempRuntime()
        assertTrue(rt.shouldBlockAdUrl("https://googleads.g.doubleclick.net/pagead/ads"))
        assertTrue(rt.shouldBlockAdUrl("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertFalse(rt.shouldBlockAdUrl("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
    }
}
