package com.winwasm64

import com.winwasm64.vpn.ALL_VPN_COUNTRIES_LIST
import com.winwasm64.vpn.AntiDpiStrategy
import com.winwasm64.vpn.BrowserDirectRepeater
import com.winwasm64.vpn.BrowsecLocalProxyServer
import com.winwasm64.vpn.BrowsecVpnService
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
    fun testBrowserRepeaterIndependentPerServerTrafficAndResetOnSwitch() {
        BrowserDirectRepeater.enabled = true
        BrowserDirectRepeater.switchCountry("LT", resetSessionTraffic = true)
        assertEquals("LT", BrowserDirectRepeater.countryCode)
        assertFalse(BrowsecLocalProxyServer.webViewProxyApplied)

        BrowserDirectRepeater.recordWebViewActivity(txEstimate = 1024L, rxEstimate = 4096L)
        val snapLt = BrowserDirectRepeater.sampleBrowserTraffic()
        assertTrue(snapLt.proxyPort > 0)
        assertNotEquals(BrowsecLocalProxyServer.activePort, snapLt.proxyPort)
        assertEquals(1024L, snapLt.txBytesTotal)
        assertEquals(4096L, snapLt.rxBytesTotal)

        // Переключаемся на другой сервер (DE): у нового сервера свой отдельный счётчик трафика!
        BrowserDirectRepeater.switchCountry("DE", resetSessionTraffic = true)
        val snapDe = BrowserDirectRepeater.sampleBrowserTraffic()
        assertEquals("DE", snapDe.activeCountry)
        assertEquals(0L, snapDe.rxBytesTotal)
        assertEquals(0L, snapDe.txBytesTotal)
    }

    @Test
    fun testAll22CountriesIncludingLatviaAndLithuaniaPresent() {
        val codes = ALL_VPN_COUNTRIES_LIST.map { it.code }.toSet()
        assertTrue(codes.contains("LT"))
        assertTrue(codes.contains("LV"))
        assertTrue(codes.contains("EE"))
        assertTrue(codes.contains("PL"))
        assertTrue(codes.contains("DE"))
        assertTrue(codes.contains("NL"))
        assertTrue(codes.contains("FI"))
        assertEquals(22, BrowserDirectRepeater.BROWSER_NODES.size)
        assertEquals(22, BrowsecLocalProxyServer.SERVER_NODES.size)
        assertEquals("Латвия", BrowserDirectRepeater.getNode("LV").countryRu)
        assertEquals("Литва", BrowserDirectRepeater.getNode("LT").countryRu)
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

        val splitRecords = BrowsecLocalProxyServer.buildSniSplitTlsRecords(record, record.size)
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
        assertTrue(FastMultiDnsResolver.isTelegramHostOrIp("149.154.167.50")) // DC2
        assertTrue(FastMultiDnsResolver.isTelegramHostOrIp("venus.web.telegram.org"))
        assertTrue(FastMultiDnsResolver.isTelegramHostOrIp("t.me"))

        // Создаём 64-байтный пакет Telegram Fake-TLS / MTProto obfuscated2
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
            strategy = AntiDpiStrategy.TCP_DESYNC_AND_SNI_2FRAG,
            bypassEnabled = true
        )
        // Побайтовое совпадение 100% — ни одного лишнего байта 0x16 0x03 не вставлено внутрь потока!
        assertArrayEquals(mtprotoHandshake, out.toByteArray())
    }

    @Test
    fun testAutoSettingsTuningAndFailoverRestoresPreviousServerToOriginalState() {
        BrowserDirectRepeater.switchCountry("LT", resetSessionTraffic = true)
        val ltState = BrowserDirectRepeater.getServerState("LT")
        ltState.restoreToOriginalState()
        assertEquals(AntiDpiStrategy.TCP_DESYNC_AND_SNI_2FRAG, ltState.currentStrategy)
        assertEquals("1.1.1.1", ltState.currentDoh)
        assertEquals(0, ltState.tuningStep)

        // Шаг 1..3: авто-смена настроек на текущем сервере LT
        BrowserDirectRepeater.triggerAutoHealOrFailover(ltState)
        assertEquals(1, ltState.tuningStep)
        assertEquals(AntiDpiStrategy.PURE_TCP_SEGMENT_DESYNC, ltState.currentStrategy)

        BrowserDirectRepeater.triggerAutoHealOrFailover(ltState)
        assertEquals(2, ltState.tuningStep)
        assertEquals(AntiDpiStrategy.TLS_RECORD_2SPLIT, ltState.currentStrategy)

        BrowserDirectRepeater.triggerAutoHealOrFailover(ltState)
        assertEquals(3, ltState.tuningStep)
        assertEquals(AntiDpiStrategy.CLEAN_IP_DIRECT_DOH, ltState.currentStrategy)

        // Шаг 4: трафика по-прежнему нет -> переход на следующий сервер (LV)
        // И ВОЗВРАТ прошлого сервера (LT) в ИСХОДНОЕ состояние!
        BrowserDirectRepeater.triggerAutoHealOrFailover(ltState)
        assertEquals("LV", BrowserDirectRepeater.countryCode)
        assertEquals(0, ltState.tuningStep)
        assertEquals(AntiDpiStrategy.TCP_DESYNC_AND_SNI_2FRAG, ltState.currentStrategy)
        assertEquals("1.1.1.1", ltState.currentDoh)
    }

    @Test
    fun testPerAppDedicatedServerAndPortIsolation() {
        // По умолчанию список пуст, пока пользователь сам не добавит приложение
        BrowsecLocalProxyServer.setAppServerCountry("org.telegram.messenger", "NL", resetTraffic = true)
        BrowsecLocalProxyServer.setAppServerCountry("com.google.android.youtube", "FI", resetTraffic = true)

        val tgProfile = BrowsecLocalProxyServer.getOrCreateAppProfile("org.telegram.messenger")
        val ytProfile = BrowsecLocalProxyServer.getOrCreateAppProfile("com.google.android.youtube")

        assertEquals("NL", tgProfile.countryCode)
        assertEquals("FI", ytProfile.countryCode)
        assertTrue(tgProfile.localPort > 0)
        assertTrue(ytProfile.localPort > 0)
        assertNotEquals("Каждое приложение должно иметь свой собственный туннельный порт", tgProfile.localPort, ytProfile.localPort)
    }
}
