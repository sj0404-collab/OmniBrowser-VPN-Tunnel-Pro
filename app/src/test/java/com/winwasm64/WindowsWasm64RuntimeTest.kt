package com.winwasm64

import com.winwasm64.vpn.BrowserDirectRepeater
import com.winwasm64.vpn.BrowsecLocalProxyServer
import com.winwasm64.vpn.BrowsecVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniBrowserAntiDpiTest {

    @Test
    fun testBrowserRepeaterNeverForwardsToLocalTunnel() {
        BrowserDirectRepeater.switchCountry("NL")
        assertEquals("NL", BrowserDirectRepeater.countryCode)
        assertTrue(BrowserDirectRepeater.enabled)
        assertFalse(BrowsecLocalProxyServer.webViewProxyApplied)
        val snap = BrowserDirectRepeater.sampleBrowserTraffic()
        assertEquals(0, snap.proxyPort)
        assertTrue(snap.activeCountryName.contains("Нидерланды"))
    }

    @Test
    fun testV24TwoRecordTlsSplitPreservesPayload() {
        val domain = "m.youtube.com".toByteArray(Charsets.US_ASCII)
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
        assertEquals("Expected v2.4.0 2-record RFC TLS split (16 bytes + remainder)", 2, splitRecords.size)
        assertEquals(5 + 16, splitRecords[0].size)
        assertEquals(5 + (body.size - 16), splitRecords[1].size)

        val reassembled = java.io.ByteArrayOutputStream()
        for (rec in splitRecords) {
            assertEquals(0x16.toByte(), rec[0])
            assertEquals(0x03.toByte(), rec[1])
            val len = ((rec[3].toInt() and 0xFF) shl 8) or (rec[4].toInt() and 0xFF)
            assertEquals(rec.size - 5, len)
            reassembled.write(rec, 5, len)
        }
        assertEquals(body.size, reassembled.size())
    }

    @Test
    fun testPerListRoutingIncludesYouTubeAndPlayMarketByDefault() {
        assertTrue(BrowsecVpnService.routedPackages.contains("com.google.android.youtube"))
        assertTrue(BrowsecVpnService.routedPackages.contains("com.android.vending"))
    }
}
