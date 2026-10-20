package com.winwasm64

import com.winwasm64.vpn.BrowsecLocalProxyServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniBrowserAntiDpiTest {

    @Test
    fun testSniSplitTlsRecordsPreservesPayloadAndSlicesDomain() {
        val domain = "m.youtube.com".toByteArray(Charsets.US_ASCII)
        val body = ByteArray(140)
        // Put fake ClientHello header + SNI domain at offset 80
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
        assertTrue("Expected ClientHello to be split into at least 4 TLS records", splitRecords.size >= 4)

        val reassembled = java.io.ByteArrayOutputStream()
        for (rec in splitRecords) {
            assertEquals(0x16.toByte(), rec[0])
            assertEquals(0x03.toByte(), rec[1])
            val len = ((rec[3].toInt() and 0xFF) shl 8) or (rec[4].toInt() and 0xFF)
            assertEquals(rec.size - 5, len)
            val chunkStr = String(rec, 5, len, Charsets.ISO_8859_1)
            assertTrue("No single TLS record may contain full m.youtube.com string", !chunkStr.contains("m.youtube.com"))
            reassembled.write(rec, 5, len)
        }
        assertEquals(body.size, reassembled.size())
    }
}
