package com.winwasm64.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Android5CompatTest {

    @Test
    fun `разбор proc net tcp находит UID владельца сокета по локальному и удалённому порту`() {
        val sample = sequenceOf(
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
            "   0: 0100007F:22AD 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10085        0 23101 1 00000000 100 0 0 10 0",
            "   1: 02ADA80A:C350 01010101:01BB 01 00000000:00000000 00:00000000 00000000 10142        0 23199 1 00000000 20 4 30 10 -1",
            "   2: 02ADA80A:C350 08080808:0050 01 00000000:00000000 00:00000000 00000000 10209        0 23210 1 00000000 20 4 30 10 -1"
        )
        // 0xC350 = 50000, 0x01BB = 443, 0x0050 = 80
        assertEquals(10142, ProcNetUidLookup.parseLines(sample, localPort = 50000, remotePort = 443))
        assertEquals(10209, ProcNetUidLookup.parseLines(sample, localPort = 50000, remotePort = 80))
        // 0x22AD = 8877
        assertEquals(10085, ProcNetUidLookup.parseLines(sample, localPort = 8877))
        assertEquals(-1, ProcNetUidLookup.parseLines(sample, localPort = 9999))
    }

    @Test
    fun `встроенный набор корневых сертификатов для Android 5-7 загружается и содержит ISRG и GTS`() {
        val pem = listOf(
            File("src/main/assets/certs/modern_roots.pem"),
            File("app/src/main/assets/certs/modern_roots.pem")
        ).firstOrNull { it.isFile }
        assertNotNull("Файл modern_roots.pem должен существовать в assets", pem)
        val tm = pem!!.inputStream().use { ModernTls.loadPemTrustManager(it) }
        assertNotNull("TrustManager должен успешно создаваться из modern_roots.pem", tm)
        val subjects = tm!!.acceptedIssuers.map { it.subjectX500Principal.name }
        assertTrue("Должен быть минимум 20 корневых сертификатов", subjects.size >= 20)
        assertTrue("Должен присутствовать ISRG Root X1 (Let's Encrypt)", subjects.any { it.contains("ISRG Root X1") })
        assertTrue("Должен присутствовать GTS Root R1 (Google Trust Services)", subjects.any { it.contains("GTS Root R1") })
    }
}
