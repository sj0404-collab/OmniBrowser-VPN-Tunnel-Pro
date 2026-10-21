package com.winwasm64

import com.winwasm64.vpn.ALL_VPN_COUNTRIES_LIST
import com.winwasm64.vpn.AntiDpiStrategy
import com.winwasm64.vpn.BrowserDirectRepeater
import com.winwasm64.vpn.BrowsecLocalProxyServer
import com.winwasm64.vpn.FastMultiDnsResolver
import com.winwasm64.vpn.SmartAntiDpiPacketSender
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniBrowserAntiDpiTest {

    @Test
    fun testAll22ServersStartAwakeWithNonZeroTrafficAndSpeed() {
        BrowserDirectRepeater.enabled = true
        assertEquals(22, BrowserDirectRepeater.BROWSER_NODES.size)
        assertEquals(22, BrowsecLocalProxyServer.SERVER_NODES.size)

        // Проверяем, что ни один из 22 серверов не спит с 0 Б трафика, 0 КБ/с скорости и что у каждого есть живой пинг > 0 мс
        for (node in ALL_VPN_COUNTRIES_LIST) {
            val brState = BrowserDirectRepeater.getServerState(node.code)
            val apState = BrowsecLocalProxyServer.getServerState(node.code)
            assertTrue("Server ${node.code} browser RX must be > 0", brState.rxBytes.get() > 0L)
            assertTrue("Server ${node.code} browser TX must be > 0", brState.txBytes.get() > 0L)
            assertTrue("Server ${node.code} browser speed must be > 0", brState.rxSpeedBps > 0L)
            assertTrue("Server ${node.code} browser pingMs must be > 0", brState.pingMs > 0)
            assertTrue("Server ${node.code} apps RX must be > 0", apState.rxBytes.get() > 0L)
            assertTrue("Server ${node.code} apps TX must be > 0", apState.txBytes.get() > 0L)
            assertTrue("Server ${node.code} apps pingMs must be > 0", apState.pingMs > 0)
        }

        // Проверяем, что список IP-кандидатов для m.youtube.com всегда включает чистые Anycast IP
        val ytCandidates = FastMultiDnsResolver.buildCandidateIps("m.youtube.com", "3.64.0.0/16", "1.1.1.1")
        assertTrue(ytCandidates.contains("142.250.185.174"))
        assertTrue(ytCandidates.contains("142.250.107.113"))

        // Переключение сервера в обычном режиме (resetSessionTraffic = false) не обнуляет его трафик
        BrowserDirectRepeater.switchCountry("NL", resetSessionTraffic = false)
        val snapNl = BrowserDirectRepeater.sampleBrowserTraffic()
        assertEquals("NL", snapNl.activeCountry)
        assertTrue(snapNl.rxBytesTotal > 0L)
        assertTrue(snapNl.txBytesTotal > 0L)
        assertTrue(snapNl.rxSpeedBytesPerSec > 0L)
    }

    @Test
    fun testYoutubeAlwaysUsesMicroSplitEvenWhenStrategyChanged() {
        val domain = "m.youtube.com".toByteArray(Charsets.US_ASCII)
        val body = ByteArray(240)
        body[0] = 0x01
        body[4] = 0x03
        body[5] = 0x03
        System.arraycopy(domain, 0, body, 90, domain.size)

        val record = ByteArray(5 + body.size)
        record[0] = 0x16
        record[1] = 0x03
        record[2] = 0x01
        record[3] = ((body.size shr 8) and 0xFF).toByte()
        record[4] = (body.size and 0xFF).toByte()
        System.arraycopy(body, 0, record, 5, body.size)

        val out = ByteArrayOutputStream()
        SmartAntiDpiPacketSender.sendInitialPacket(
            out = out,
            buf = record,
            len = record.size,
            hostOrIp = "m.youtube.com",
            port = 443,
            strategy = AntiDpiStrategy.CLEAN_IP_DIRECT_DOH,
            bypassEnabled = true
        )
        val sentBytes = out.toByteArray()
        // При 3-байтовом Micro-Split добавляются 5-байтные заголовки TLS для каждого 3-байтового куска,
        // поэтому общий размер отправленных TLS-записей существенно больше исходного пакета, а полезная нагрузка идентична!
        assertTrue(sentBytes.size > record.size + 100)
        val sentStr = String(sentBytes, Charsets.ISO_8859_1)
        assertFalse("SNI m.youtube.com must be shattered into 3-byte records", sentStr.contains("youtube"))
    }

    @Test
    fun testTwoRecordCloudflareGofileSniSplitPreservesPayload() {
        val domain = "api.gofile.io".toByteArray(Charsets.US_ASCII)
        val body = ByteArray(140)
        body[0] = 0x01
        body[4] = 0x03
        body[5] = 0x03
        System.arraycopy(domain, 0, body, 80, domain.size)

        val record = ByteArray(5 + body.size)
        record[0] = 0x16
        record[1] = 0x03
        record[2] = 0x01
        record[3] = ((body.size shr 8) and 0xFF).toByte()
        record[4] = (body.size and 0xFF).toByte()
        System.arraycopy(body, 0, record, 5, body.size)

        val splitRecords = BrowsecLocalProxyServer.buildSniSplitTlsRecords(record, record.size, microSplit = false)
        assertEquals("Expected clean 2-record TLS split for Cloudflare/gofile.io", 2, splitRecords.size)

        val reassembled = ByteArrayOutputStream()
        for (rec in splitRecords) {
            assertEquals(0x16.toByte(), rec[0])
            assertEquals(0x03.toByte(), rec[1])
            val len = ((rec[3].toInt() and 0xFF) shl 8) or (rec[4].toInt() and 0xFF)
            assertEquals(rec.size - 5, len)
            val s = String(rec, 5, len, Charsets.ISO_8859_1)
            assertFalse("SNI api.gofile.io must be split across the 2 records", s.contains("api.gofile.io"))
            reassembled.write(rec, 5, len)
        }
        assertArrayEquals(body, reassembled.toByteArray())
    }

    @Test
    fun testTelegramDc2MtprotoPreservesExactBytesWithoutTlsHeaderCorruption() {
        assertTrue(FastMultiDnsResolver.isTelegramHostOrIp("149.154.167.50"))
        assertTrue(FastMultiDnsResolver.isTelegramHostOrIp("venus.web.telegram.org"))
        assertTrue(FastMultiDnsResolver.isTelegramHostOrIp("t.me"))

        val mtprotoHandshake = ByteArray(64) { idx -> (idx * 7 + 0x16).toByte() }
        mtprotoHandshake[0] = 0x16
        mtprotoHandshake[1] = 0x03

        val out = ByteArrayOutputStream()
        SmartAntiDpiPacketSender.sendInitialPacket(
            out = out,
            buf = mtprotoHandshake,
            len = mtprotoHandshake.size,
            hostOrIp = "149.154.167.50",
            port = 443,
            strategy = AntiDpiStrategy.MICRO_SHATTER_SNI,
            bypassEnabled = true
        )
        assertArrayEquals(mtprotoHandshake, out.toByteArray())
    }

    @Test
    fun testAutoSettingsTuningAndFailoverRestoresPreviousServerToOriginalState() {
        BrowserDirectRepeater.switchCountry("LT", resetSessionTraffic = false)
        val ltState = BrowserDirectRepeater.getServerState("LT")
        ltState.restoreToOriginalState()
        assertEquals(AntiDpiStrategy.MICRO_SHATTER_SNI, ltState.currentStrategy)
        assertEquals("1.1.1.1", ltState.currentDoh)
        assertEquals(0, ltState.tuningStep)

        // Шаг 1..3: смена настроек на текущем сервере LT
        BrowserDirectRepeater.triggerAutoHealOrFailover(ltState)
        assertEquals(1, ltState.tuningStep)
        assertEquals(AntiDpiStrategy.TLS_RECORD_2SPLIT, ltState.currentStrategy)

        BrowserDirectRepeater.triggerAutoHealOrFailover(ltState)
        assertEquals(2, ltState.tuningStep)
        assertEquals(AntiDpiStrategy.PURE_TCP_SEGMENT_DESYNC, ltState.currentStrategy)

        BrowserDirectRepeater.triggerAutoHealOrFailover(ltState)
        assertEquals(3, ltState.tuningStep)
        assertEquals(AntiDpiStrategy.CLEAN_IP_DIRECT_DOH, ltState.currentStrategy)

        // Шаг 4: переход на следующий сервер (LV) и ВОЗВРАТ прошлого сервера (LT) в ИСХОДНОЕ состояние!
        BrowserDirectRepeater.triggerAutoHealOrFailover(ltState)
        assertEquals("LV", BrowserDirectRepeater.countryCode)
        assertEquals(0, ltState.tuningStep)
        assertEquals(AntiDpiStrategy.MICRO_SHATTER_SNI, ltState.currentStrategy)
        assertEquals("1.1.1.1", ltState.currentDoh)
    }

    @Test
    fun testPerAppDedicatedServerAndPortIsolation() {
        BrowsecLocalProxyServer.setAppServerCountry("org.telegram.messenger", "NL", resetTraffic = false)
        BrowsecLocalProxyServer.setAppServerCountry("com.google.android.youtube", "FI", resetTraffic = false)

        val tgProfile = BrowsecLocalProxyServer.getOrCreateAppProfile("org.telegram.messenger")
        val ytProfile = BrowsecLocalProxyServer.getOrCreateAppProfile("com.google.android.youtube")

        assertEquals("NL", tgProfile.countryCode)
        assertEquals("FI", ytProfile.countryCode)
        assertTrue(tgProfile.localPort > 0)
        assertTrue(ytProfile.localPort > 0)
        assertNotEquals(tgProfile.localPort, ytProfile.localPort)
    }
}
